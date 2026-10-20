package io.github.jambolo.transmogrifier.llm

import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaMessage
import com.openai.helpers.ResponseAccumulator
import io.github.jambolo.transmogrifier.agent.AgentPrompt
import io.github.jambolo.transmogrifier.llm.anthropic.AnthropicRequests
import io.github.jambolo.transmogrifier.llm.anthropic.AnthropicTurnMapper
import io.github.jambolo.transmogrifier.llm.openai.OpenAiRequests
import io.github.jambolo.transmogrifier.llm.openai.OpenAiTurnMapper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Maps recorded-format SSE streams without any network. */
class ProviderMapperTest {
    private fun sse(name: String): List<String> =
        javaClass.classLoader!!.getResource("fixtures/$name")!!
            .readText()
            .lines()
            .filter { it.startsWith("data: ") }
            .map { it.removePrefix("data: ") }

    private fun request(provider: ProviderId, history: List<JsonObject> = emptyList()) =
        TurnRequest(
            model = if (provider == ProviderId.ANTHROPIC) "claude-opus-5-5" else "gpt-6.1-sol",
            effort = Effort.HIGH,
            maxOutputTokens = 64_000,
            systemPrompt = AgentPrompt.SYSTEM,
            tools = AgentPrompt.TOOLS,
            history = ProviderHistory(provider, history),
            input = TurnInput(text = "A greeting page")
        )

    @Test
    fun anthropicRequestIncludesWebTools() {
        val params = AnthropicRequests.params(request(ProviderId.ANTHROPIC))
        val tools = params.tools().get()
        assertEquals(AgentPrompt.TOOLS.size + 2, tools.size)
        assertEquals(true, tools[tools.size - 2].isWebSearchTool20250305())
        assertEquals(true, tools.last().isWebFetchTool20250910())
    }

    @Test
    fun anthropicPauseTurnResumesWithoutUserMessage() {
        assertEquals(
            StopReason.PAUSED,
            AnthropicRequests.stopReason(
                com.anthropic.models.beta.messages.BetaStopReason.PAUSE_TURN
            )
        )
        assertEquals(
            emptyList<JsonObject>(),
            AnthropicRequests.userMessages(TurnInput(resume = true))
        )
    }

    @Test
    fun openAiRequestIncludesWebSearch() {
        val tools = OpenAiRequests.params(request(ProviderId.OPENAI)).tools().get()
        assertEquals(AgentPrompt.TOOLS.size + 1, tools.size)
        assertEquals(true, tools.last().isWebSearch())
        assertEquals(emptyList<JsonObject>(), OpenAiRequests.inputItems(TurnInput(resume = true)))
    }

    private val expectedCall = TurnEvent.ToolCall(
        "toolu_01",
        "write_file",
        """{"path":"index.html","content":"<h1>Hi</h1>"}"""
    )

    @Test
    fun anthropicToolTurn() {
        val request = request(ProviderId.ANTHROPIC)
        val mapper = AnthropicTurnMapper(request)
        val accumulator = BetaMessageAccumulator.create()
        val events = sse("anthropic-tool-turn.sse").flatMap { json ->
            val event = AnthropicRequests.parseEvent(json)
            accumulator.accumulate(event)
            mapper.onEvent(event)
        } + mapper.finish(accumulator.message())

        val completed = events.last() as TurnEvent.Completed
        assertEquals(
            listOf(
                TurnEvent.TextDelta("Planning the layout."),
                TurnEvent.TextDelta("\n\nI'll create the page."),
                TurnEvent.ToolCallStarted("toolu_01", "write_file"),
                TurnEvent.ToolInputDelta("toolu_01", """{"path": "index.html", """),
                TurnEvent.ToolInputDelta("toolu_01", """"content": "<h1>Hi</h1>"}"""),
                expectedCall
            ),
            events.dropLast(1).map {
                if (it is TurnEvent.ToolCall) it.copy(inputJson = normalize(it.inputJson)) else it
            }
        )
        assertEquals(StopReason.TOOL_USE, completed.stopReason)
        assertEquals(Usage(1200, 120, 1000, 0), completed.usage)

        val (user, assistant) = completed.history.items.map { it.jsonObject }
        assertEquals(AnthropicRequests.userMessage(request.input), user)
        val thinking = assistant.getValue("content").jsonArray[0].jsonObject
        // Thinking blocks must round-trip unchanged, signature included.
        assertEquals("sig-abc", thinking.getValue("signature").jsonPrimitive.content)
        assertEquals("Planning the layout.", thinking.getValue("thinking").jsonPrimitive.content)

        // The stored history must be sent as the next request's messages unchanged.
        val next = request.copy(history = completed.history, input = TurnInput(text = "More"))
        val body = com.anthropic.core.jsonMapper().writeValueAsString(
            AnthropicRequests.params(next)._body()
        )
        assertEquals(
            completed.history.items + AnthropicRequests.userMessage(next.input),
            Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray.toList()
        )
    }

    @Test
    fun anthropicToolResultsComeBeforeText() {
        val message = AnthropicRequests.userMessage(
            TurnInput(
                listOf(ToolResult("toolu_01", "No such file", isError = true)),
                "Also add a footer"
            )
        )
        assertEquals(
            Json.parseToJsonElement(
                """{"role":"user","content":[
                    {"type":"tool_result","tool_use_id":"toolu_01","content":"No such file","is_error":true},
                    {"type":"text","text":"Also add a footer"}]}"""
            ),
            message
        )
    }

    @Test
    fun anthropicDropsDeclinedBlocksBeforeFallback() {
        val message = com.anthropic.core.jsonMapper().readValue(
            """{"id":"msg_02","type":"message","role":"assistant","model":"claude-opus-5",
               "content":[
                 {"type":"thinking","thinking":"","signature":"s1"},
                 {"type":"text","text":"Partial"},
                 {"type":"tool_use","id":"toolu_x","name":"read_file","input":{"path":"a.js"}},
                 {"type":"fallback","from":{"model":"claude-opus-5-5"},"to":{"model":"claude-opus-5"}},
                 {"type":"text","text":"Final"}],
               "stop_reason":"end_turn","stop_sequence":null,
               "usage":{"input_tokens":10,"output_tokens":5}}""",
            BetaMessage::class.java
        )
        val content = AnthropicRequests.assistantMessage(message).getValue("content").jsonArray
        assertEquals(
            listOf("Partial", "Final"),
            content.map {
                it.jsonObject.getValue("text").jsonPrimitive.content
            }
        )
        assertEquals(
            emptyList<TurnEvent.ToolCall>(),
            AnthropicRequests.toolCalls(AnthropicRequests.assistantMessage(message))
        )
    }

    @Test
    fun anthropicKeepsWebSearchResults() {
        val message = com.anthropic.core.jsonMapper().readValue(
            """{"id":"msg_03","type":"message","role":"assistant","model":"claude-opus-5-5",
               "content":[
                 {"type":"server_tool_use","id":"srvtoolu_1","name":"web_search",
                  "input":{"query":"fetch cors"}},
                 {"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
                   {"type":"web_search_result","url":"https://example.com","title":"T",
                    "encrypted_content":"enc","page_age":null}]},
                 {"type":"text","text":"Because of CORS."}],
               "stop_reason":"end_turn","stop_sequence":null,
               "usage":{"input_tokens":10,"output_tokens":5}}""",
            BetaMessage::class.java
        )
        val content = AnthropicRequests.assistantMessage(message).getValue("content").jsonArray
        assertEquals(
            listOf("server_tool_use", "web_search_tool_result", "text"),
            content.map { it.jsonObject.getValue("type").jsonPrimitive.content }
        )
    }

    @Test
    fun anthropicStreamKeepsWebSearchResults() {
        val accumulator = BetaMessageAccumulator.create()
        listOf(
            """{"type":"message_start","message":{"id":"msg_04","type":"message","role":"assistant",
               "model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,
               "usage":{"input_tokens":10,"output_tokens":1}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use",
               "id":"srvtoolu_1","name":"web_search","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta",
               "partial_json":"{\"query\": \"fetch cors\"}"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{
               "type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[
               {"type":"web_search_result","url":"https://example.com","title":"T",
                "encrypted_content":"enc","page_age":null}]}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"text_delta",
               "text":"Because of CORS."}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},
               "usage":{"output_tokens":5}}""",
            """{"type":"message_stop"}"""
        ).forEach { accumulator.accumulate(AnthropicRequests.parseEvent(it)) }
        val content = AnthropicRequests.assistantMessage(accumulator.message())
            .getValue("content").jsonArray
        assertEquals(
            listOf("server_tool_use", "web_search_tool_result", "text"),
            content.map { it.jsonObject.getValue("type").jsonPrimitive.content }
        )
    }

    @Test
    fun anthropicReplayDropsUnresolvedServerToolCalls() {
        val assistant = Json.parseToJsonElement(
            """{"role":"assistant","content":[
                 {"type":"tool_use","id":"toolu_1","name":"list_files","input":{}},
                 {"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}},
                 {"type":"server_tool_use","id":"srvtoolu_2","name":"web_search","input":{}},
                 {"type":"web_search_tool_result","tool_use_id":"srvtoolu_2","content":[]}]}"""
        ).jsonObject
        val content = AnthropicRequests.withoutUnresolvedServerTools(assistant)
            .jsonObject.getValue("content").jsonArray
        assertEquals(
            listOf("tool_use", "server_tool_use", "web_search_tool_result"),
            content.map { it.jsonObject.getValue("type").jsonPrimitive.content }
        )
        assertEquals(
            "srvtoolu_2",
            content[1].jsonObject.getValue("id").jsonPrimitive.content
        )
    }

    @Test
    fun anthropicRequestHasStrictEagerTools() {
        val body = com.anthropic.core.jsonMapper().writeValueAsString(
            AnthropicRequests.params(request(ProviderId.ANTHROPIC))._body()
        )
        val json = Json.parseToJsonElement(body).jsonObject
        val tool = json.getValue("tools").jsonArray[0].jsonObject
        assertEquals("true", tool.getValue("strict").toString())
        assertEquals("true", tool.getValue("eager_input_streaming").toString())
        assertEquals(""""default"""", json.getValue("fallbacks").toString())
        assertEquals(
            """"updates"""",
            json.getValue("thinking").jsonObject.getValue("display").toString()
        )
    }

    @Test
    fun openAiToolTurn() {
        val earlier =
            listOf(
                Json.parseToJsonElement(
                    """{"type":"message","role":"user","content":"Hi"}"""
                ).jsonObject
            )
        val request = request(ProviderId.OPENAI, earlier)
        val mapper = OpenAiTurnMapper(request)
        val accumulator = ResponseAccumulator.create()
        val events = sse("openai-tool-turn.sse").flatMap { json ->
            val event = OpenAiRequests.parseEvent(json)
            accumulator.accumulate(event)
            mapper.onEvent(event)
        } + mapper.finish(accumulator.response())

        val completed = events.last() as TurnEvent.Completed
        assertEquals(
            listOf(
                TurnEvent.TextDelta("I'll create the page."),
                TurnEvent.ToolCallStarted("call_1", "write_file"),
                TurnEvent.ToolInputDelta("call_1", """{"path":"index.html","""),
                TurnEvent.ToolInputDelta("call_1", """"content":"<h1>Hi</h1>"}"""),
                expectedCall.copy(callId = "call_1")
            ),
            events.dropLast(1)
        )
        assertEquals(StopReason.TOOL_USE, completed.stopReason)
        assertEquals(Usage(200, 120, 1000, 0), completed.usage)

        // History: earlier items, this turn's input, then every output item verbatim.
        val items = completed.history.items.map { it.jsonObject }
        assertEquals(earlier + OpenAiRequests.inputItems(request.input), items.take(2))
        assertEquals(
            listOf("reasoning", "message", "function_call"),
            items.drop(2).map {
                it.getValue("type").jsonPrimitive.content
            }
        )
        assertEquals("enc-xyz", items[2].getValue("encrypted_content").jsonPrimitive.content)
        assertEquals("commentary", items[3].getValue("phase").jsonPrimitive.content)

        // The stored history must be sent as the next request's input unchanged.
        val next = request.copy(history = completed.history, input = TurnInput(text = "More"))
        val body = com.openai.core.jsonMapper().writeValueAsString(
            OpenAiRequests.params(next)._body()
        )
        assertEquals(
            completed.history.items + OpenAiRequests.inputItems(next.input),
            Json.parseToJsonElement(body).jsonObject.getValue("input").jsonArray.toList()
        )
    }

    private fun normalize(json: String) = Json.parseToJsonElement(json).toString()
}
