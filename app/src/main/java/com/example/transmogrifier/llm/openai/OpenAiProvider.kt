package com.example.transmogrifier.llm.openai

import com.example.transmogrifier.llm.LlmException
import com.example.transmogrifier.llm.LlmProvider
import com.example.transmogrifier.llm.ModelInfo
import com.example.transmogrifier.llm.ProviderId
import com.example.transmogrifier.llm.TurnEvent
import com.example.transmogrifier.llm.TurnRequest
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.errors.InternalServerException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIServiceException
import com.openai.errors.PermissionDeniedException
import com.openai.errors.RateLimitException
import com.openai.errors.SseException
import com.openai.errors.UnauthorizedException
import com.openai.helpers.ResponseAccumulator
import com.openai.models.responses.ResponseStatus
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** OpenAI coding models through the official Java SDK's streaming Responses API. */
class OpenAiProvider(
    private val apiKey: suspend () -> String?,
    private val clientFactory: (String) -> OpenAIClient = ::defaultClient
) : LlmProvider {
    override val id = ProviderId.OPENAI

    private var cached: Pair<String, OpenAIClient>? = null

    override suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val client = client()
        val today = LocalDate.now()
        translateErrors {
            client.models().list().autoPager()
                .filter { CODING_MODELS.matches(it.id()) }
                .filter { model -> model.shutdownDate().map { it.isAfter(today) }.orElse(true) }
                .map { ModelInfo(it.id(), it.id()) }
                .sortedByDescending { it.id }
        }
    }

    override fun runTurn(request: TurnRequest): Flow<TurnEvent> = flow {
        val client = client()
        val mapper = OpenAiTurnMapper(request)
        val accumulator = ResponseAccumulator.create()
        try {
            translateErrors {
                coroutineScope {
                    val stream = client.responses().createStreaming(OpenAiRequests.params(request))
                    // Reading the stream blocks, so closing it is how Stop interrupts a turn in progress.
                    val closer = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            stream.close()
                        }
                    }
                    try {
                        for (event in stream.stream()) {
                            ensureActive()
                            accumulator.accumulate(event)
                            event.error().ifPresent {
                                throw LlmException.Server(IllegalStateException(it.message()))
                            }
                            mapper.onEvent(event).forEach { emit(it) }
                        }
                    } finally {
                        closer.cancel()
                    }
                }
            }
        } catch (e: LlmException) {
            // Stop closes the stream, which surfaces as an I/O error; report it as the cancellation it is.
            currentCoroutineContext().ensureActive()
            throw e
        }
        val response = accumulator.response()
        if (response.status().orElse(null) == ResponseStatus.FAILED) {
            val message = response.error().map { it.message() }.orElse("The response failed")
            throw LlmException.Server(IllegalStateException(message))
        }
        mapper.finish(response).forEach { emit(it) }
    }.flowOn(Dispatchers.IO)

    @Synchronized
    private fun cachedClient(key: String): OpenAIClient = cached?.takeIf { it.first == key }?.second
        ?: clientFactory(key).also { cached = key to it }

    private suspend fun client(): OpenAIClient {
        val key = apiKey() ?: throw LlmException.MissingKey()
        return cachedClient(key)
    }

    private companion object {
        val CODING_MODELS =
            Regex("""gpt-6(\.\d+)?-(astra|sol|luna)|gpt-5\.6-(sol|terra|luna)|gpt-[\d.]+-codex""")

        // Retries happen in the engine, where they can be shown to the user.
        fun defaultClient(key: String): OpenAIClient =
            OpenAIOkHttpClient.builder().apiKey(key).maxRetries(0).build()

        inline fun <T> translateErrors(block: () -> T): T = try {
            block()
        } catch (e: UnauthorizedException) {
            throw LlmException.InvalidKey(e)
        } catch (e: PermissionDeniedException) {
            throw LlmException.InvalidKey(e)
        } catch (e: RateLimitException) {
            throw LlmException.RateLimited(e)
        } catch (e: InternalServerException) {
            throw LlmException.Server(e)
        } catch (e: SseException) {
            // An error sent mid-stream after a 200; almost always server-side.
            throw LlmException.Server(e)
        } catch (e: OpenAIServiceException) {
            throw if (e.statusCode() >= 500) LlmException.Server(e) else LlmException.Rejected(e)
        } catch (e: OpenAIIoException) {
            throw LlmException.Network(e)
        } catch (e: IOException) {
            throw LlmException.Network(e)
        }
    }
}
