package com.example.transmogrifier.agent

import com.example.transmogrifier.llm.ToolResult
import com.example.transmogrifier.llm.TurnEvent
import com.example.transmogrifier.project.Workspace
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolExecutorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val executor by lazy { ToolExecutor(Workspace(File(temp.root, "site"))) }

    private suspend fun run(name: String, input: String): ToolResult =
        executor.execute(TurnEvent.ToolCall("call", name, input))

    @Test
    fun runsEachTool() = runTest {
        assertEquals(ToolResult("call", "No files yet.", false), run("list_files", "{}"))
        assertFalse(run("write_file", """{"path":"index.html","content":"<p>a</p>"}""").isError)
        assertFalse(
            run("edit_file", """{"path":"index.html","old_text":"a","new_text":"b"}""").isError
        )
        assertEquals("<p>b</p>", run("read_file", """{"path":"index.html"}""").content)
        assertEquals("index.html (8 bytes)", run("list_files", "{}").content)
        assertFalse(run("delete_file", """{"path":"index.html"}""").isError)
        assertTrue(run("read_file", """{"path":"index.html"}""").isError)
    }

    @Test
    fun invalidInputBecomesErrorResults() = runTest {
        val bad = run("write_file", """{"path":"a.html","content":"unterminated""")
        assertTrue(bad.isError)
        // Returned as JSON so the model sees exactly what it sent.
        assertEquals(
            """{"path":"a.html","content":"unterminated""",
            Json.parseToJsonElement(
                bad.content
            ).jsonObject.getValue("INVALID_JSON").jsonPrimitive.content
        )
        assertTrue(run("write_file", """{"path":"a.html"}""").isError)
        assertTrue(run("read_file", """{"path":"a.html","extra":1}""").isError)
        assertTrue(run("read_file", """{"path":3}""").isError)
        assertTrue(run("read_file", "[]").isError)
        assertTrue(run("format_disk", "{}").isError)
        assertTrue(run("write_file", """{"path":"../x.html","content":""}""").isError)
    }

    @Test
    fun pathFromPartialInput() {
        assertNull(ToolExecutor.pathFromPartialInput("""{"path": "ind"""))
        assertEquals(
            "index.html",
            ToolExecutor.pathFromPartialInput("""{"path": "index.html", "con""")
        )
        assertEquals("a\"b.js", ToolExecutor.pathFromPartialInput("""{"path":"a\"b.js"}"""))
    }
}
