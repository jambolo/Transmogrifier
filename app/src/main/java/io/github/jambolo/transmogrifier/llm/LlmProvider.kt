package io.github.jambolo.transmogrifier.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The supported providers. Adding one takes a constant here, an [LlmProvider] adapter in its own `llm` subpackage,
 * its registration in `AppContainer`, and a label in `ui/Labels.kt`; the compiler flags any label that's missing.
 *
 * A provider that isn't [enabled] keeps its adapter code but isn't offered in the app or used for generation.
 */
@Serializable
enum class ProviderId(val defaultModel: String, val enabled: Boolean = true) {
    ANTHROPIC("claude-opus-5-5"),

    // Disabled until generation has been verified with a real key.
    OPENAI("gpt-6.1-sol", enabled = false);

    companion object {
        val enabledEntries: List<ProviderId> = entries.filter { it.enabled }
    }
}

data class ModelInfo(val id: String, val displayName: String)

@Serializable
enum class Effort { LOW, MEDIUM, HIGH, XHIGH, MAX }

/** Provider-neutral tool definition; each provider maps it to its own wire format. */
data class ToolDefinition(val name: String, val description: String, val inputSchema: JsonObject)

/**
 * Conversation history in the provider's own wire format. Opaque outside the provider and only ever appended to:
 * Claude requires thinking blocks to round-trip unchanged.
 */
@Serializable
data class ProviderHistory(val provider: ProviderId, val items: List<JsonElement> = emptyList())

/**
 * The next user turn: results for every tool call of the previous assistant turn (which must come first), then
 * optional text.
 */
@Serializable
data class TurnInput(val toolResults: List<ToolResult> = emptyList(), val text: String? = null) {
    init {
        require(toolResults.isNotEmpty() || text != null) { "Empty turn input" }
    }
}

@Serializable
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

    /** A tool call began streaming; its input follows as [ToolInputDelta]s. */
    data class ToolCallStarted(val callId: String, val name: String) : TurnEvent

    data class ToolInputDelta(val callId: String, val partialJson: String) : TurnEvent

    /** A complete tool call. Emitted after the turn's output has finished streaming, before [Completed]. */
    data class ToolCall(val callId: String, val name: String, val inputJson: String) : TurnEvent

    /** [history] is the request history with this turn's input and the model's output appended. */
    data class Completed(
        val stopReason: StopReason,
        val usage: Usage,
        val history: ProviderHistory
    ) : TurnEvent
}

/** Provider failures in a provider-neutral form, so the engine can decide whether to retry. */
sealed class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MissingKey : LlmException("No API key saved")

    class InvalidKey(cause: Throwable) : LlmException(cause.message ?: "Invalid API key", cause)

    class RateLimited(cause: Throwable) : LlmException(cause.message ?: "Rate limited", cause)

    class Server(cause: Throwable) : LlmException(cause.message ?: "Server error", cause)

    class Network(cause: Throwable) : LlmException(cause.message ?: "Network error", cause)

    /** The request itself was rejected (400, 404, ...); retrying unchanged will not help. */
    class Rejected(cause: Throwable) : LlmException(cause.message ?: "Request rejected", cause)

    val retryable: Boolean get() = this is RateLimited || this is Server || this is Network
}

interface LlmProvider {
    val id: ProviderId

    /** @throws LlmException */
    suspend fun listModels(): List<ModelInfo>

    /** Streams one turn. Fails with [LlmException]; no history is produced for a failed turn. */
    fun runTurn(request: TurnRequest): Flow<TurnEvent>
}
