package io.github.jambolo.transmogrifier.agent

import io.github.jambolo.transmogrifier.llm.LlmException
import io.github.jambolo.transmogrifier.llm.LlmProvider
import io.github.jambolo.transmogrifier.llm.ModelInfo
import io.github.jambolo.transmogrifier.llm.ProviderId
import io.github.jambolo.transmogrifier.llm.StopReason
import io.github.jambolo.transmogrifier.llm.ToolResult
import io.github.jambolo.transmogrifier.llm.TurnEvent
import io.github.jambolo.transmogrifier.llm.TurnInput
import io.github.jambolo.transmogrifier.llm.TurnRequest
import io.github.jambolo.transmogrifier.llm.Usage
import io.github.jambolo.transmogrifier.project.FileProjectStore
import io.github.jambolo.transmogrifier.project.NoticeKind
import io.github.jambolo.transmogrifier.project.ProjectId
import io.github.jambolo.transmogrifier.project.TranscriptEntry
import io.github.jambolo.transmogrifier.settings.FakeSettingsRepository
import io.github.jambolo.transmogrifier.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentEngineTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val store by lazy { FileProjectStore(temp.root) }
    private val requests = mutableListOf<TurnRequest>()

    /** A provider driven by a script: each turn's events are computed from the request. */
    private inner class ScriptedProvider(val script: suspend (TurnRequest) -> List<TurnEvent>) :
        LlmProvider {
        override val id = ProviderId.ANTHROPIC

        override suspend fun listModels() = listOf(ModelInfo("m", "M"))

        override fun runTurn(request: TurnRequest): Flow<TurnEvent> = flow {
            requests += request
            script(request).forEach { emit(it) }
        }
    }

    private fun completed(request: TurnRequest, stop: StopReason) = TurnEvent.Completed(
        stop,
        Usage(100, 10, 80),
        request.history.copy(
            items =
            request.history.items + JsonPrimitive("in") + JsonPrimitive("out")
        )
    )

    private fun write(id: String, path: String) =
        TurnEvent.ToolCall(id, "write_file", """{"path":"$path","content":"x"}""")

    private suspend fun TestScope.engine(
        provider: LlmProvider,
        settings: Settings = Settings(),
        settingsRepository: FakeSettingsRepository = FakeSettingsRepository(settings)
    ): Pair<AgentEngine, ProjectId> {
        val engine = AgentEngine(
            store,
            settingsRepository,
            mapOf(
                ProviderId.ANTHROPIC to provider
            ),
            listOf(1, 1)
        )
        return engine to store.create("App", ProviderId.ANTHROPIC, "m").id
    }

    @Test
    fun runsToolsAndLoopsUntilEndTurn() = runTest {
        val (engine, id) = engine(
            ScriptedProvider { request ->
                if (request.input.text != null) {
                    listOf(
                        TurnEvent.TextDelta("Building."),
                        write("1", "index.html"),
                        write("2", "js/app.js"),
                        completed(request, StopReason.TOOL_USE)
                    )
                } else {
                    listOf(TurnEvent.TextDelta("Done."), completed(request, StopReason.END_TURN))
                }
            }
        )
        val events = engine.generate(id, "A counter").toList()

        assertEquals(
            TurnInput(
                listOf(
                    ToolResult("1", "Wrote index.html", false),
                    ToolResult("2", "Wrote js/app.js", false)
                )
            ),
            requests[1].input
        )
        assertEquals(
            listOf(
                TranscriptEntry.Prompt("A counter"),
                TranscriptEntry.Reply("Building."),
                TranscriptEntry.Usage(100, 10, 80),
                TranscriptEntry.Tool("write_file", "index.html"),
                TranscriptEntry.Tool("write_file", "js/app.js"),
                TranscriptEntry.Reply("Done."),
                TranscriptEntry.Usage(100, 10, 80)
            ),
            store.transcript(id).first()
        )
        assertTrue(GenerationEvent.ToolRunning("write_file", "js/app.js") in events)
        val conversation = store.conversation(id)!!
        assertNull(conversation.pending)
        assertEquals(4, conversation.history.items.size)
        assertEquals(2, store.workspace(id).listFiles().size)
    }

    @Test
    fun iterationLimitKeepsPendingResultsForResume() = runTest {
        val provider = ScriptedProvider { request ->
            listOf(
                TurnEvent.ToolCall("c${requests.size}", "list_files", "{}"),
                completed(request, StopReason.TOOL_USE)
            )
        }
        val (engine, id) = engine(provider, Settings(maxToolIterations = 2))
        engine.generate(id, "Go").toList()

        assertEquals(
            TranscriptEntry.Notice(NoticeKind.ITERATION_LIMIT),
            store.transcript(id).first().last()
        )
        val pending = store.conversation(id)!!.pending!!
        assertEquals(listOf("c2"), pending.toolResults.map { it.callId })

        engine.generate(id, null).toList()
        assertEquals(pending, requests[2].input)
    }

    @Test
    fun stopKeepsInputAndResumes() = runTest {
        var hang = true
        val streaming = CompletableDeferred<Unit>()
        val (engine, id) = engine(
            ScriptedProvider { request ->
                if (hang) {
                    streaming.complete(Unit)
                    awaitCancellation()
                }
                listOf(completed(request, StopReason.END_TURN))
            }
        )
        val job = launch { engine.generate(id, "Go").toList() }
        // The store does real IO, so wait for the turn to start rather than for the test scheduler.
        streaming.await()
        job.cancel()
        job.join()

        assertEquals(
            TranscriptEntry.Notice(NoticeKind.STOPPED),
            store.transcript(id).first().last()
        )
        assertEquals(TurnInput(text = "Go"), store.conversation(id)!!.pending)

        hang = false
        engine.generate(id, "Go on").toList()
        assertEquals(TurnInput(text = "Go on"), requests.last().input)
        assertNull(store.conversation(id)!!.pending)
    }

    @Test
    fun instructionsAreSentOnceAndAgainWhenChanged() = runTest {
        val settings = FakeSettingsRepository(Settings(appInstructions = " Use large text. "))
        val (engine, id) = engine(
            ScriptedProvider { listOf(completed(it, StopReason.END_TURN)) },
            settingsRepository = settings
        )
        engine.generate(id, "A counter").toList()
        engine.generate(id, "Make it blue").toList()
        settings.update { it.copy(appInstructions = "") }
        engine.generate(id, "Add a reset").toList()

        val texts = requests.map { it.input.text!! }
        assertTrue(texts[0].contains("<instructions>\nUse large text.\n</instructions>"))
        assertTrue(texts[0].endsWith("\n\nA counter"))
        assertEquals("Make it blue", texts[1])
        assertTrue(texts[2].startsWith("The user removed their instructions"))
        assertTrue(texts[2].endsWith("\n\nAdd a reset"))
        assertTrue(requests.all { it.systemPrompt == AgentPrompt.SYSTEM })
        assertEquals("", store.conversation(id)!!.instructions)
        assertEquals(TranscriptEntry.Prompt("A counter"), store.transcript(id).first().first())
    }

    @Test
    fun truncatedToolCallsAreNotRun() = runTest {
        val (engine, id) = engine(
            ScriptedProvider { request ->
                if (request.input.text != null) {
                    listOf(write("1", "index.html"), completed(request, StopReason.MAX_TOKENS))
                } else {
                    listOf(completed(request, StopReason.END_TURN))
                }
            }
        )
        engine.generate(id, "Go").toList()
        assertTrue(store.workspace(id).listFiles().isEmpty())
        assertTrue(requests[1].input.toolResults.single().isError)
    }

    @Test
    fun retriesRetryableErrorsVisibly() = runTest {
        var failures = 1
        val (engine, id) = engine(
            ScriptedProvider { request ->
                if (failures-- > 0) throw LlmException.RateLimited(RuntimeException("slow down"))
                listOf(completed(request, StopReason.END_TURN))
            }
        )
        val events = engine.generate(id, "Go").toList()
        assertEquals(GenerationEvent.Retrying(1, 1, "slow down"), events.first())
        assertEquals(TranscriptEntry.Usage(100, 10, 80), store.transcript(id).first().last())
    }

    @Test
    fun authFailureIsReported() = runTest {
        val (engine, id) = engine(ScriptedProvider { throw LlmException.MissingKey() })
        engine.generate(id, "Go").toList()
        val notice = store.transcript(id).first().last() as TranscriptEntry.Notice
        assertEquals(NoticeKind.AUTH_FAILED, notice.kind)
        assertEquals(TurnInput(text = "Go"), store.conversation(id)!!.pending)
    }

    @Test
    fun projectOfDisabledProviderIsReported() = runTest {
        val (engine, _) = engine(ScriptedProvider { error("Must not run") })
        val id = store.create("Old", ProviderId.OPENAI, "m").id
        engine.generate(id, "Go").toList()
        assertEquals(
            listOf(
                TranscriptEntry.Prompt("Go"),
                TranscriptEntry.Notice(NoticeKind.PROVIDER_UNAVAILABLE)
            ),
            store.transcript(id).first()
        )
    }
}
