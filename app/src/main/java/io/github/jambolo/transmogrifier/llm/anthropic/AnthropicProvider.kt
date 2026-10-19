package io.github.jambolo.transmogrifier.llm.anthropic

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.BetaMessageAccumulator
import io.github.jambolo.transmogrifier.llm.LlmException
import io.github.jambolo.transmogrifier.llm.LlmProvider
import io.github.jambolo.transmogrifier.llm.ModelInfo
import io.github.jambolo.transmogrifier.llm.ProviderId
import io.github.jambolo.transmogrifier.llm.TurnEvent
import io.github.jambolo.transmogrifier.llm.TurnRequest
import java.io.IOException
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

/** Claude through the official Java SDK's streaming beta Messages API. */
class AnthropicProvider(
    private val apiKey: suspend () -> String?,
    private val clientFactory: (String) -> AnthropicClient = ::defaultClient
) : LlmProvider {
    override val id = ProviderId.ANTHROPIC

    private var cached: Pair<String, AnthropicClient>? = null

    override suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val client = client()
        translateErrors {
            client.models().list().autoPager().map { ModelInfo(it.id(), it.displayName()) }
        }
    }

    override fun runTurn(request: TurnRequest): Flow<TurnEvent> = flow {
        val client = client()
        val mapper = AnthropicTurnMapper(request)
        val accumulator = BetaMessageAccumulator.create()
        try {
            translateErrors {
                coroutineScope {
                    val stream = client.beta().messages().createStreaming(
                        AnthropicRequests.params(request)
                    )
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
        mapper.finish(accumulator.message()).forEach { emit(it) }
    }.flowOn(Dispatchers.IO)

    @Synchronized
    private fun cachedClient(key: String): AnthropicClient =
        cached?.takeIf { it.first == key }?.second
            ?: clientFactory(key).also { cached = key to it }

    private suspend fun client(): AnthropicClient {
        val key = apiKey() ?: throw LlmException.MissingKey()
        return cachedClient(key)
    }

    private companion object {
        // Retries happen in the engine, where they can be shown to the user.
        fun defaultClient(key: String): AnthropicClient =
            AnthropicOkHttpClient.builder().apiKey(key).maxRetries(0).build()

        inline fun <T> translateErrors(block: () -> T): T = try {
            block()
        } catch (e: UnauthorizedException) {
            throw LlmException.InvalidKey(e)
        } catch (e: PermissionDeniedException) {
            throw LlmException.InvalidKey(e)
        } catch (e: RateLimitException) {
            throw LlmException.RateLimited(e)
        } catch (e: AnthropicServiceException) {
            // 5xx and 529 overloaded, including errors sent mid-stream.
            throw if (e.statusCode() >= 500) LlmException.Server(e) else LlmException.Rejected(e)
        } catch (e: AnthropicIoException) {
            throw LlmException.Network(e)
        } catch (e: IOException) {
            throw LlmException.Network(e)
        }
    }
}
