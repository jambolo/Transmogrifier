package com.example.transmogrifier.agent

import com.example.transmogrifier.llm.ToolDefinition
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The system prompt and tools. Both must stay byte-identical between requests: they form the cached prompt prefix,
 * and Claude's thinking blocks are only valid while the prefix is unchanged.
 */
object AgentPrompt {
    val SYSTEM = """
        You build small browser apps that run inside an Android app's built-in browser. The user describes an app or a
        change in plain language; you implement it by editing the app's files with the tools provided.

        The app is a static site:
        - The entry point is index.html. Use relative paths only. There is no build step and no package manager.
        - Plain HTML, CSS, and JavaScript; ES modules are fine.
        - The app is self-contained: no remote scripts, stylesheets, fonts, or network requests. They are blocked.
        - Allowed file types: .html, .css, .js, .mjs, .json, .svg, .txt, .md. Keep files small and focused.
        - Persist user data in localStorage.

        Design for a phone held in portrait: touch-friendly controls at least 44 px tall, readable text, no hover-only
        interactions, and layouts that fit the screen width. Respect prefers-color-scheme. Don't use alert(), confirm(),
        or prompt() as part of the app's flow; build the UI in the page instead.

        Work in small steps and keep index.html runnable after every change. Read a file before editing it unless you
        wrote it in this conversation. Prefer edit_file for small changes and write_file for new files or rewrites.

        Before your first tool call, say in one sentence what you're about to do. When you finish, summarize what
        changed in two or three short sentences for someone who can't see the code.
    """.trimIndent()

    val TOOLS: List<ToolDefinition> = listOf(
        tool("list_files", "List every file in the app with its size in bytes.") {},
        tool("read_file", "Read a file's full text.") {
            path()
        },
        tool("write_file", "Create a file or replace its entire contents.") {
            path()
            stringProperty("content", "The complete new file contents.")
        },
        tool(
            "edit_file",
            "Replace exactly one occurrence of old_text with new_text in a file. Fails if " +
                "old_text is missing or appears more than once; include enough surrounding " +
                "text to make it unique."
        ) {
            path()
            stringProperty("old_text", "Exact text to replace, including whitespace.")
            stringProperty("new_text", "Replacement text.")
        },
        tool("delete_file", "Delete a file.") {
            path()
        }
    )

    private class SchemaBuilder {
        val properties = mutableMapOf<String, JsonObject>()

        fun stringProperty(name: String, description: String) {
            properties[name] = buildJsonObject {
                put("type", "string")
                put("description", description)
            }
        }

        fun path() =
            stringProperty("path", "File path relative to the app root, for example css/style.css.")
    }

    private fun tool(
        name: String,
        description: String,
        schema: SchemaBuilder.() -> Unit
    ): ToolDefinition {
        val properties = SchemaBuilder().apply(schema).properties
        return ToolDefinition(
            name,
            description,
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    properties.forEach { (key, value) -> put(key, value) }
                }
                putJsonArray("required") { properties.keys.forEach { add(it) } }
                put("additionalProperties", false)
            }
        )
    }
}
