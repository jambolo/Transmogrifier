package io.github.jambolo.transmogrifier.llm.openai

import com.openai.core.JsonValue
import com.openai.core.jsonMapper
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.responses.FunctionTool
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ResponseIncludable
import com.openai.models.responses.ResponseStatus
import com.openai.models.responses.ResponseStreamEvent
import io.github.jambolo.transmogrifier.llm.Effort
import io.github.jambolo.transmogrifier.llm.StopReason
import io.github.jambolo.transmogrifier.llm.ToolDefinition
import io.github.jambolo.transmogrifier.llm.TurnEvent
import io.github.jambolo.transmogrifier.llm.TurnInput
import io.github.jambolo.transmogrifier.llm.TurnRequest
import io.github.jambolo.transmogrifier.llm.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Request building and stream mapping for the Responses API, kept free of networking so it can be tested. */
internal object OpenAiRequests {
    private val mapper = jsonMapper()

    // Explicit implicit-mode caching is only accepted from GPT-5.6 on; older models cache automatically.
    private val cacheOptionsModels = Regex("""gpt-(6(\.\d+)?|5\.6)-.*""")

    /**
     * History is replayed statelessly (`store = false`): every earlier input and output item is sent again,
     * including encrypted reasoning, so nothing depends on server-side conversation state.
     */
    fun params(request: TurnRequest): ResponseCreateParams {
        // Sent as a raw tree: a typed round-trip through ResponseInputItem adds fields under R8.
        val items = JsonArray(request.history.items + inputItems(request.input))
        val builder = ResponseCreateParams.builder()
            .model(request.model)
            .instructions(request.systemPrompt)
            .input(JsonValue.fromJsonNode(mapper.readTree(items.toString())))
            .tools(request.tools.map { com.openai.models.responses.Tool.ofFunction(tool(it)) })
            .maxOutputTokens(request.maxOutputTokens.toLong())
            .store(false)
            .addInclude(ResponseIncludable.REASONING_ENCRYPTED_CONTENT)
            .reasoning(Reasoning.builder().effort(effort(request.effort)).build())
            .parallelToolCalls(true)
        if (cacheOptionsModels.matches(request.model)) {
            builder.promptCacheOptions(
                ResponseCreateParams.PromptCacheOptions.builder()
                    .mode(ResponseCreateParams.PromptCacheOptions.Mode.IMPLICIT)
                    .build()
            )
        }
        return builder.build()
    }

    private fun tool(definition: ToolDefinition): FunctionTool {
        val parameters = FunctionTool.Parameters.builder()
        mapper.readTree(definition.inputSchema.toString()).properties().forEach { entry ->
            parameters.putAdditionalProperty(entry.key, JsonValue.fromJsonNode(entry.value))
        }
        return FunctionTool.builder()
            .name(definition.name)
            .description(definition.description)
            .parameters(parameters.build())
            .strict(true)
            .build()
    }

    private fun effort(effort: Effort) = when (effort) {
        Effort.LOW -> ReasoningEffort.LOW
        Effort.MEDIUM -> ReasoningEffort.MEDIUM
        Effort.HIGH -> ReasoningEffort.HIGH
        Effort.XHIGH -> ReasoningEffort.XHIGH
        Effort.MAX -> ReasoningEffort.MAX
    }

    /** Input items in wire format: tool outputs first, then the user's text. */
    fun inputItems(input: TurnInput): List<JsonObject> = input.toolResults.map { result ->
        buildJsonObject {
            put("type", "function_call_output")
            put("call_id", result.callId)
            // Function outputs have no error flag.
            put("output", if (result.isError) "Error: ${result.content}" else result.content)
        }
    } + listOfNotNull(
        input.text?.let { text ->
            buildJsonObject {
                put("type", "message")
                put("role", "user")
                put("content", text)
            }
        }
    )

    /** Output items are appended to history exactly as received, so reasoning and message phases round-trip. */
    fun outputItems(response: Response): List<JsonElement> =
        response.output().map { Json.parseToJsonElement(mapper.writeValueAsString(it)) }

    fun stopReason(response: Response): StopReason {
        val incomplete = response.incompleteDetails().flatMap { it.reason() }.orElse(null)
        val refused = response.output().any { item ->
            item.message().map { message -> message.content().any { it.isRefusal() } }.orElse(false)
        }
        val reason = Response.IncompleteDetails.Reason
        return when {
            incomplete == reason.MAX_OUTPUT_TOKENS -> StopReason.MAX_TOKENS
            incomplete == reason.CONTENT_FILTER || refused -> StopReason.REFUSAL
            response.output().any { it.isFunctionCall() } -> StopReason.TOOL_USE
            response.status().orElse(null) == ResponseStatus.COMPLETED -> StopReason.END_TURN
            else -> StopReason.OTHER
        }
    }

    fun toolCalls(response: Response): List<TurnEvent.ToolCall> = response.output()
        .filter { it.isFunctionCall() }
        .map { it.asFunctionCall() }
        .map { TurnEvent.ToolCall(it.callId(), it.name(), it.arguments()) }

    fun usage(response: Response): Usage {
        val usage = response.usage().orElse(null) ?: return Usage(0, 0)
        val cached = usage.inputTokensDetails().cachedTokens()
        val cacheWrites = usage.inputTokensDetails()._cacheWriteTokens().asKnown().orElse(0L)
        // OpenAI counts cached tokens inside input_tokens; Usage.inputTokens excludes them, as Claude does.
        return Usage(usage.inputTokens() - cached, usage.outputTokens(), cached, cacheWrites)
    }

    fun parseEvent(json: String): ResponseStreamEvent =
        mapper.readValue(json, ResponseStreamEvent::class.java)
}

/** Maps one streamed response to [TurnEvent]s. */
internal class OpenAiTurnMapper(private val request: TurnRequest) {
    private val callIds = mutableMapOf<String, String>()
    private var emittedText = false
    private var separatorDue = false

    fun onEvent(event: ResponseStreamEvent): List<TurnEvent> {
        event.outputItemAdded().orElse(null)?.let { added ->
            val item = added.item()
            if (item.isMessage()) separatorDue = emittedText
            val call = item.functionCall().orElse(null) ?: return emptyList()
            call.id().ifPresent { callIds[it] = call.callId() }
            return listOf(TurnEvent.ToolCallStarted(call.callId(), call.name()))
        }
        event.outputTextDelta().orElse(null)?.let { delta ->
            if (delta.delta().isEmpty()) return emptyList()
            val prefix = if (separatorDue) "\n\n" else ""
            separatorDue = false
            emittedText = true
            return listOf(TurnEvent.TextDelta(prefix + delta.delta()))
        }
        event.functionCallArgumentsDelta().orElse(null)?.let { delta ->
            val callId = callIds[delta.itemId()] ?: return emptyList()
            return listOf(TurnEvent.ToolInputDelta(callId, delta.delta()))
        }
        return emptyList()
    }

    fun finish(response: Response): List<TurnEvent> {
        val items = request.history.items + OpenAiRequests.inputItems(request.input) +
            OpenAiRequests.outputItems(response)
        return OpenAiRequests.toolCalls(response) + TurnEvent.Completed(
            OpenAiRequests.stopReason(response),
            OpenAiRequests.usage(response),
            request.history.copy(items = items)
        )
    }
}
