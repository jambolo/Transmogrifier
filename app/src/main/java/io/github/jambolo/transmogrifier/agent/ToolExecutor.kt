package io.github.jambolo.transmogrifier.agent

import io.github.jambolo.transmogrifier.llm.ToolDefinition
import io.github.jambolo.transmogrifier.llm.ToolResult
import io.github.jambolo.transmogrifier.llm.TurnEvent
import io.github.jambolo.transmogrifier.project.Workspace
import io.github.jambolo.transmogrifier.project.WorkspaceResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Validates tool input against its schema and runs it on the [Workspace]. Every failure, including input that is
 * not valid JSON, becomes an error result for the model rather than an exception.
 */
class ToolExecutor(
    private val workspace: Workspace,
    tools: List<ToolDefinition> = AgentPrompt.TOOLS
) {
    private val schemas = tools.associate { tool ->
        val required = tool.inputSchema.getValue("required").jsonArray
        tool.name to required.map { it.jsonPrimitive.content }
    }

    suspend fun execute(call: TurnEvent.ToolCall): ToolResult {
        val input = when (val parsed = parse(call)) {
            is Parsed.Invalid -> return ToolResult(call.callId, parsed.message, isError = true)
            is Parsed.Valid -> parsed.input
        }
        val result = when (call.name) {
            "list_files" -> WorkspaceResult.Ok(
                workspace.listFiles().joinToString("\n") { "${it.path} (${it.sizeBytes} bytes)" }
                    .ifEmpty { "No files yet." }
            )

            "read_file" -> workspace.read(input.getValue("path"))

            "write_file" -> workspace.write(input.getValue("path"), input.getValue("content"))
                .map { "Wrote ${input["path"]}" }

            "edit_file" -> workspace.edit(
                input.getValue("path"),
                input.getValue("old_text"),
                input.getValue("new_text")
            ).map { "Edited ${input["path"]}" }

            "delete_file" -> workspace.delete(input.getValue("path")).map {
                "Deleted ${input["path"]}"
            }

            else -> WorkspaceResult.Error("Unknown tool ${call.name}")
        }
        return when (result) {
            is WorkspaceResult.Ok -> ToolResult(call.callId, result.value, isError = false)
            is WorkspaceResult.Error -> ToolResult(call.callId, result.message, isError = true)
        }
    }

    private sealed interface Parsed {
        data class Valid(val input: Map<String, String>) : Parsed

        data class Invalid(val message: String) : Parsed
    }

    private fun parse(call: TurnEvent.ToolCall): Parsed {
        val required = schemas[call.name] ?: return Parsed.Invalid("Unknown tool ${call.name}")
        val json = try {
            Json.parseToJsonElement(call.inputJson) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return Parsed.Invalid(invalidJson(call.inputJson))
        val unexpected = json.keys - required.toSet()
        val missing = required.filter { (json[it] as? JsonPrimitive)?.isString != true }
        return when {
            unexpected.isNotEmpty() -> Parsed.Invalid(
                "Unexpected parameters: ${unexpected.joinToString()}"
            )
            missing.isNotEmpty() -> Parsed.Invalid(
                "Missing string parameters: ${missing.joinToString()}"
            )
            else -> Parsed.Valid(json.mapValues { it.value.jsonPrimitive.content })
        }
    }

    // Built with the JSON library so quotes in the bad input are escaped.
    private fun invalidJson(raw: String) = buildJsonObject { put("INVALID_JSON", raw) }.toString()

    private fun <T, R> WorkspaceResult<T>.map(transform: (T) -> R): WorkspaceResult<R> =
        when (this) {
            is WorkspaceResult.Ok -> WorkspaceResult.Ok(transform(value))
            is WorkspaceResult.Error -> this
        }

    companion object {
        private val pathPattern = Regex("\"path\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

        /** Best-effort path from a tool call's partial input JSON, for the live activity log. */
        fun pathFromPartialInput(partialJson: String): String? =
            pathPattern.find(partialJson)?.groupValues?.get(1)?.let {
                runCatching { Json.decodeFromString<String>("\"$it\"") }.getOrNull()
            }
    }
}
