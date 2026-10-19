package com.example.transmogrifier.llm.anthropic

import com.anthropic.core.jsonMapper
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaMessageParam
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.BetaThinkingBlockBinding
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive
import com.anthropic.models.beta.messages.BetaThinkingPrefixMismatchBehavior
import com.anthropic.models.beta.messages.BetaTool
import com.anthropic.models.beta.messages.BetaToolUnion
import com.anthropic.models.beta.messages.MessageCreateParams
import com.example.transmogrifier.llm.Effort
import com.example.transmogrifier.llm.StopReason
import com.example.transmogrifier.llm.ToolDefinition
import com.example.transmogrifier.llm.TurnEvent
import com.example.transmogrifier.llm.TurnInput
import com.example.transmogrifier.llm.TurnRequest
import com.example.transmogrifier.llm.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Request building and stream mapping for the Messages API, kept free of networking so it can be tested. */
internal object AnthropicRequests {
    private val mapper = jsonMapper()

    // Features that only some models accept; the rest of the request is the same for every model.
    private val fallbackModels =
        setOf("claude-fable-5-1", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5")
    private val progressUpdateModels =
        setOf("claude-fable-5-1", "claude-fable-5", "claude-opus-5-5", "claude-sonnet-5-5")
    private val preservedThinkingModels =
        setOf("claude-fable-5-1", "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")

    fun params(request: TurnRequest): MessageCreateParams {
        val model = request.model
        val thinking = BetaThinkingConfigAdaptive.builder()
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(request.maxOutputTokens.toLong())
            .outputConfig(BetaOutputConfig.builder().effort(effort(request.effort)).build())
            .systemOfBetaTextBlockParams(
                listOf(BetaTextBlockParam.builder().text(request.systemPrompt).build())
            )
            .tools(
                request.tools.map(::tool).map {
                    BetaToolUnion.ofBetaTool(it)
                }
            )
            // Automatic caching of the longest stable prefix: tools, system prompt, and history.
            .cacheControl(BetaCacheControlEphemeral.builder().build())
            .messages((request.history.items + userMessage(request.input)).map(::toParam))
        if (model in fallbackModels) {
            builder.addBeta(
                AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01
            ).fallbacks(BetaFallbacksParam.ofDefault())
        }
        if (model in progressUpdateModels) {
            // Notes between tool calls come back as thinking blocks; "updates" makes them readable.
            builder.addBeta(AnthropicBeta.THINKING_DISPLAY_UPDATES_2026_08_18)
            thinking.display(BetaThinkingConfigAdaptive.Display.UPDATES)
        }
        if (model in preservedThinkingModels) {
            // History is append-only, so a prefix mismatch is a bug worth surfacing rather than hiding.
            builder.addBeta(AnthropicBeta.THINKING_BINDING_CONTROLS_2026_08_01)
            thinking.blockBinding(
                BetaThinkingBlockBinding.builder()
                    .prefixMismatchBehavior(BetaThinkingPrefixMismatchBehavior.ERROR)
                    .build()
            )
        }
        return builder.thinking(thinking.build()).build()
    }

    private fun tool(definition: ToolDefinition): BetaTool = BetaTool.builder()
        .name(definition.name)
        .description(definition.description)
        .inputSchema(
            mapper.readValue(definition.inputSchema.toString(), BetaTool.InputSchema::class.java)
        )
        .strict(true)
        .eagerInputStreaming(true)
        .build()

    private fun effort(effort: Effort) = when (effort) {
        Effort.LOW -> BetaOutputConfig.Effort.LOW
        Effort.MEDIUM -> BetaOutputConfig.Effort.MEDIUM
        Effort.HIGH -> BetaOutputConfig.Effort.HIGH
        Effort.XHIGH -> BetaOutputConfig.Effort.XHIGH
        Effort.MAX -> BetaOutputConfig.Effort.MAX
    }

    /** The user message in wire format: tool results first, as the API requires, then any text. */
    fun userMessage(input: TurnInput): JsonObject = buildJsonObject {
        put("role", "user")
        putJsonArray("content") {
            for (result in input.toolResults) {
                addJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", result.callId)
                    put("content", result.content)
                    if (result.isError) put("is_error", true)
                }
            }
            input.text?.let { text ->
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
        }
    }

    private fun toParam(item: JsonElement): BetaMessageParam =
        mapper.readValue(item.toString(), BetaMessageParam::class.java)

    /**
     * The assistant message to append to history. After a mid-output refusal fallback, blocks before the last
     * `fallback` marker other than text belong to the declined model and must not be echoed back.
     */
    fun assistantMessage(message: BetaMessage): JsonObject {
        val param = Json.parseToJsonElement(mapper.writeValueAsString(message.toParam())).jsonObject
        val content = param["content"]?.jsonArray ?: return param
        val boundary = content.indexOfLast { it.type == "fallback" }
        if (boundary < 0) return param
        val before = content.take(boundary).filter { it.type == "text" }
        val kept = before + content.drop(boundary + 1)
        return JsonObject(param + ("content" to JsonArray(kept)))
    }

    private val JsonElement.type get() = (this as? JsonObject)?.get("type")?.jsonPrimitive?.content

    fun stopReason(reason: BetaStopReason?) = when (reason) {
        BetaStopReason.END_TURN, BetaStopReason.STOP_SEQUENCE -> StopReason.END_TURN
        BetaStopReason.TOOL_USE -> StopReason.TOOL_USE
        BetaStopReason.MAX_TOKENS -> StopReason.MAX_TOKENS
        BetaStopReason.REFUSAL -> StopReason.REFUSAL
        else -> StopReason.OTHER
    }

    /** Tool calls in an appended assistant message, in order. */
    fun toolCalls(assistant: JsonObject): List<TurnEvent.ToolCall> =
        assistant["content"]?.jsonArray.orEmpty().mapNotNull { block ->
            val obj = block as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.content != "tool_use") return@mapNotNull null
            TurnEvent.ToolCall(
                obj.getValue("id").jsonPrimitive.content,
                obj.getValue("name").jsonPrimitive.content,
                (obj["input"] ?: JsonObject(emptyMap())).toString()
            )
        }

    fun parseEvent(json: String): BetaRawMessageStreamEvent =
        mapper.readValue(json, BetaRawMessageStreamEvent::class.java)
}

/** Maps one streamed turn to [TurnEvent]s. */
internal class AnthropicTurnMapper(private val request: TurnRequest) {
    private val toolIds = mutableMapOf<Long, String>()
    private var emittedText = false
    private var separatorDue = false

    fun onEvent(event: BetaRawMessageStreamEvent): List<TurnEvent> {
        event.contentBlockStart().orElse(null)?.let { start ->
            separatorDue = emittedText
            val toolUse = start.contentBlock().toolUse().orElse(null) ?: return emptyList()
            toolIds[start.index()] = toolUse.id()
            return listOf(TurnEvent.ToolCallStarted(toolUse.id(), toolUse.name()))
        }

        val delta = event.contentBlockDelta().orElse(null) ?: return emptyList()
        val text = delta.delta().text().map { it.text() }
            // Progress notes between tool calls arrive as thinking blocks under display "updates".
            .or { delta.delta().thinking().map { it.thinking() } }
            .orElse(null)
        if (!text.isNullOrEmpty()) {
            val prefix = if (separatorDue) "\n\n" else ""
            separatorDue = false
            emittedText = true
            return listOf(TurnEvent.TextDelta(prefix + text))
        }
        val json = delta.delta().inputJson().map { it.partialJson() }.orElse(null)
        val id = toolIds[delta.index()]
        if (json == null || id == null) return emptyList()
        return listOf(TurnEvent.ToolInputDelta(id, json))
    }

    fun finish(message: BetaMessage): List<TurnEvent> {
        val assistant = AnthropicRequests.assistantMessage(message)
        val hasContent = assistant["content"]?.jsonArray?.isNotEmpty() == true
        // A refusal before any output leaves nothing to append; an empty assistant turn would be rejected later.
        val items = if (hasContent) {
            request.history.items + AnthropicRequests.userMessage(request.input) + assistant
        } else {
            request.history.items
        }
        val usage = message.usage()
        return AnthropicRequests.toolCalls(assistant) + TurnEvent.Completed(
            AnthropicRequests.stopReason(message.stopReason().orElse(null)),
            Usage(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadInputTokens().orElse(0),
                usage.cacheCreationInputTokens().orElse(0)
            ),
            request.history.copy(items = items)
        )
    }
}
