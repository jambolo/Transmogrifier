package com.example.transmogrifier.agent

import com.example.transmogrifier.llm.LlmException
import com.example.transmogrifier.llm.LlmProvider
import com.example.transmogrifier.llm.ProviderHistory
import com.example.transmogrifier.llm.ProviderId
import com.example.transmogrifier.llm.StopReason
import com.example.transmogrifier.llm.ToolResult
import com.example.transmogrifier.llm.TurnEvent
import com.example.transmogrifier.llm.TurnInput
import com.example.transmogrifier.llm.TurnRequest
import com.example.transmogrifier.project.Conversation
import com.example.transmogrifier.project.NoticeKind
import com.example.transmogrifier.project.ProjectId
import com.example.transmogrifier.project.ProjectStore
import com.example.transmogrifier.project.TranscriptEntry
import com.example.transmogrifier.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * The generation loop: streams a turn, runs the tool calls against the project's workspace, sends all results back
 * in one message, and repeats until the model ends its turn, a limit is hit, or the collector is cancelled.
 *
 * The conversation is saved before every turn with the input about to be sent as `pending`, so a stopped or failed
 * generation can resume, and the history only grows by complete turns.
 */
class AgentEngine(
    private val store: ProjectStore,
    private val settingsRepository: SettingsRepository,
    private val providers: Map<ProviderId, LlmProvider>,
    private val retryDelaysSeconds: List<Int> = listOf(2, 5, 15),
    private val systemPrompt: String = AgentPrompt.SYSTEM
) : GenerationEngine {
    override fun generate(
        projectId: ProjectId,
        prompt: String?,
        fixErrors: Boolean
    ): Flow<GenerationEvent> = flow {
        val project = store.project(projectId).first() ?: return@flow
        val settings = settingsRepository.settings.first()
        val provider = providers.getValue(project.provider)
        // A conversation from another provider can't be continued in this provider's format.
        var conversation = store.conversation(projectId)
            ?.takeIf { it.history.provider == project.provider }
            ?: Conversation(ProviderHistory(project.provider))

        val promptEntry = prompt?.let { TranscriptEntry.Prompt(it, fixErrors) }
        if (promptEntry != null) store.appendTranscript(projectId, promptEntry)
        val text = prompt?.let { if (fixErrors) fixErrorsPrompt(it) else it }
        val pending = conversation.pending
        var input = when {
            pending == null -> text?.let { TurnInput(text = it) } ?: return@flow
            text == null -> pending
            // A new prompt replaces unsent text but must still carry unsent tool results.
            else -> TurnInput(pending.toolResults, text)
        }
        val workspace = store.workspace(projectId)
        val executor = ToolExecutor(workspace)

        // Results for tool calls being executed when the collector is cancelled.
        var runningCalls = emptyList<TurnEvent.ToolCall>()
        val finishedResults = mutableListOf<ToolResult>()
        try {
            for (iteration in 1..settings.maxToolIterations) {
                conversation = conversation.copy(pending = input)
                store.saveConversation(projectId, conversation)
                val request = TurnRequest(
                    project.model,
                    settings.effort,
                    settings.maxOutputTokens,
                    systemPrompt,
                    AgentPrompt.TOOLS,
                    conversation.history,
                    input
                )
                val turn = runTurnWithRetries(provider, request)
                conversation = Conversation(turn.completed.history)
                store.saveConversation(projectId, conversation)
                if (turn.text.isNotBlank()) {
                    store.appendTranscript(projectId, TranscriptEntry.Reply(turn.text.trim()))
                }
                val usage = turn.completed.usage
                store.appendTranscript(
                    projectId,
                    TranscriptEntry.Usage(
                        usage.inputTokens,
                        usage.outputTokens,
                        usage.cacheReadInputTokens
                    )
                )
                emit(GenerationEvent.TurnPersisted)

                val stop = turn.completed.stopReason
                if (turn.toolCalls.isEmpty()) {
                    when (stop) {
                        StopReason.MAX_TOKENS -> notice(projectId, NoticeKind.TRUNCATED)
                        StopReason.REFUSAL -> notice(projectId, NoticeKind.REFUSED)
                        else -> Unit
                    }
                    return@flow
                }
                // Never run tools from a truncated or refused turn: their input may be cut off.
                val results = if (stop == StopReason.MAX_TOKENS || stop == StopReason.REFUSAL) {
                    turn.toolCalls.map {
                        ToolResult(it.callId, notRunReason(stop), isError = true)
                    }
                } else {
                    runningCalls = turn.toolCalls
                    finishedResults.clear()
                    turn.toolCalls.map { call ->
                        emit(GenerationEvent.ToolRunning(call.name, pathOf(call)))
                        executor.execute(call).also { result ->
                            finishedResults += result
                            store.appendTranscript(
                                projectId,
                                TranscriptEntry.Tool(
                                    call.name,
                                    pathOf(call),
                                    result.content.takeIf {
                                        result.isError
                                    }
                                )
                            )
                            emit(GenerationEvent.TurnPersisted)
                        }
                    }.also { runningCalls = emptyList() }
                }
                input = TurnInput(results)
                if (stop == StopReason.REFUSAL) {
                    store.saveConversation(projectId, conversation.copy(pending = input))
                    notice(projectId, NoticeKind.REFUSED)
                    return@flow
                }
            }
            store.saveConversation(projectId, conversation.copy(pending = input))
            notice(projectId, NoticeKind.ITERATION_LIMIT)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (runningCalls.isNotEmpty()) {
                    val done = finishedResults.associateBy { it.callId }
                    val results = runningCalls.map {
                        done[it.callId]
                            ?: ToolResult(
                                it.callId,
                                "Stopped by the user before this ran.",
                                isError = true
                            )
                    }
                    store.saveConversation(
                        projectId,
                        conversation.copy(pending = TurnInput(results))
                    )
                }
                notice(projectId, NoticeKind.STOPPED)
            }
            throw e
        } catch (e: LlmException) {
            val kind = if (e is LlmException.MissingKey || e is LlmException.InvalidKey) {
                NoticeKind.AUTH_FAILED
            } else {
                NoticeKind.FAILED
            }
            store.appendTranscript(projectId, TranscriptEntry.Notice(kind, e.message))
        }
    }

    private class CompletedTurn(
        val text: String,
        val toolCalls: List<TurnEvent.ToolCall>,
        val completed: TurnEvent.Completed
    )

    /** Streams one turn, retrying retryable failures with backoff. */
    private suspend fun FlowCollector<GenerationEvent>.runTurnWithRetries(
        provider: LlmProvider,
        request: TurnRequest
    ): CompletedTurn {
        var attempt = 0
        while (true) {
            val text = StringBuilder()
            val toolCalls = mutableListOf<TurnEvent.ToolCall>()
            val toolNames = mutableMapOf<String, String>()
            val partialInputs = mutableMapOf<String, StringBuilder>()
            var completed: TurnEvent.Completed? = null
            try {
                provider.runTurn(request).collect { event ->
                    when (event) {
                        is TurnEvent.TextDelta -> {
                            text.append(event.text)
                            emit(GenerationEvent.TextDelta(event.text))
                        }

                        is TurnEvent.ToolCallStarted -> {
                            toolNames[event.callId] = event.name
                            emit(GenerationEvent.ToolRunning(event.name, null))
                        }

                        is TurnEvent.ToolInputDelta -> {
                            // Show the path as soon as it has streamed, ahead of a long file body.
                            val partial = partialInputs.getOrPut(event.callId) { StringBuilder() }
                            val hadPath =
                                ToolExecutor.pathFromPartialInput(partial.toString()) != null
                            partial.append(event.partialJson)
                            val path = ToolExecutor.pathFromPartialInput(partial.toString())
                            val name = toolNames[event.callId]
                            if (!hadPath && path != null && name != null) {
                                emit(GenerationEvent.ToolRunning(name, path))
                            }
                        }

                        is TurnEvent.ToolCall -> toolCalls += event

                        is TurnEvent.Completed -> completed = event
                    }
                }
                val done = completed
                    ?: throw LlmException.Server(IllegalStateException("The response ended early"))
                return CompletedTurn(text.toString(), toolCalls, done)
            } catch (e: LlmException) {
                if (!e.retryable || attempt >= retryDelaysSeconds.size) throw e
                val delaySeconds = retryDelaysSeconds[attempt++]
                emit(GenerationEvent.Retrying(attempt, delaySeconds, e.message.orEmpty()))
                delay(delaySeconds * 1_000L)
            }
        }
    }

    private suspend fun notice(projectId: ProjectId, kind: NoticeKind) =
        store.appendTranscript(projectId, TranscriptEntry.Notice(kind))

    private fun pathOf(call: TurnEvent.ToolCall) = ToolExecutor.pathFromPartialInput(call.inputJson)

    private fun notRunReason(stop: StopReason) = when (stop) {
        StopReason.MAX_TOKENS ->
            "Not run: your output hit the token limit, so this input may be cut off. Write smaller pieces."
        else -> "Not run: the turn was declined."
    }

    private fun fixErrorsPrompt(log: String) =
        "Running the app produced these errors in the browser console. Fix their causes.\n\n$log"
}
