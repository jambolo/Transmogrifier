package com.example.transmogrifier.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
enum class ProviderId { ANTHROPIC, OPENAI }

data class ModelInfo(val id: String, val displayName: String)

enum class Effort { LOW, MEDIUM, HIGH, XHIGH, MAX }

/** Provider-neutral tool definition; each provider maps it to its own wire format. */
data class ToolDefinition(val name: String, val description: String, val inputSchema: JsonObject)

/**
 * Conversation history in the provider's own wire format. Opaque outside the provider and only ever appended to:
 * Claude requires thinking blocks to round-trip unchanged.
 */
@Serializable
data class ProviderHistory(val provider: ProviderId, val items: List<JsonElement> = emptyList())

sealed interface TurnInput {
    data class UserPrompt(val text: String) : TurnInput

    /** All results for the previous turn's tool calls, sent together in one message. */
    data class ToolResults(val results: List<ToolResult>) : TurnInput
}

data class ToolResult(val callId: String, val content: String, val isError: Boolean)

data class TurnRequest(
    val model: String,
    val effort: Effort,
    val maxOutputTokens: Int,
    val systemPrompt: String,
    val tools: List<ToolDefinition>,
    val history: ProviderHistory,
    val input: TurnInput
)

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER }

data class Usage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadInputTokens: Long = 0,
    val cacheCreationInputTokens: Long = 0
)

sealed interface TurnEvent {
    data class TextDelta(val text: String) : TurnEvent

    data class ToolInputDelta(val callId: String, val partialJson: String) : TurnEvent

    data class ToolCall(val callId: String, val name: String, val inputJson: String) : TurnEvent

    /** [history] is the request history with this turn's input and the model's output appended. */
    data class Completed(
        val stopReason: StopReason,
        val usage: Usage,
        val history: ProviderHistory
    ) : TurnEvent
}

interface LlmProvider {
    val id: ProviderId

    suspend fun listModels(): List<ModelInfo>

    fun runTurn(request: TurnRequest): Flow<TurnEvent>
}
