package io.github.jambolo.transmogrifier.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import io.github.jambolo.transmogrifier.R
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Speaks for the page with Android's [TextToSpeech], because WebView's `speechSynthesis` doesn't work. A
 * document-start script replaces `speechSynthesis` with one that sends requests here through a web message
 * listener limited to the site's origin. Must be used on the main thread.
 */
internal class SpeechBridge(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val script = context.resources.openRawResource(R.raw.speech_synthesis)
        .bufferedReader()
        .use { it.readText() }

    /** Null while the engine starts. */
    private var ready: Boolean? = null
    private val waiting = mutableListOf<() -> Unit>()
    private val utterances = mutableMapOf<String, Utterance>()
    private var nextKey = 0L
    private val tts = TextToSpeech(context.applicationContext) { status ->
        main.post { onInit(status) }
    }

    // The caller checks for WEB_MESSAGE_LISTENER and DOCUMENT_START_SCRIPT.
    @SuppressLint("RequiresFeature")
    fun install(webView: WebView, rules: Set<String>) {
        WebViewCompat.addWebMessageListener(webView, OBJECT, rules) { _, message, _, _, proxy ->
            onRequest(message.data, proxy)
        }
        WebViewCompat.addDocumentStartJavaScript(webView, script, rules)
    }

    /** Stops speaking and tells the page its utterances were interrupted. */
    fun stop() {
        if (utterances.isEmpty()) return
        val stopped = utterances.values.toList()
        utterances.clear()
        tts.stop()
        stopped.forEach { it.reply("error", error = "interrupted") }
    }

    fun shutdown() {
        utterances.clear()
        waiting.clear()
        tts.shutdown()
    }

    private fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready == true) tts.setOnUtteranceProgressListener(Progress())
        waiting.forEach { it() }
        waiting.clear()
    }

    private fun onRequest(data: String?, proxy: JavaScriptReplyProxy) {
        val request = data?.let {
            runCatching { json.decodeFromString(Request.serializer(), it) }.getOrNull()
        } ?: return
        val handle = {
            when (request.type) {
                "voices" -> proxy.postMessage(voices())
                "speak" -> speak(request, Utterance(proxy, request.id))
                "cancel" -> cancel()
            }
        }
        if (ready == null) waiting += handle else handle()
    }

    private fun speak(request: Request, utterance: Utterance) {
        val error = when {
            ready != true -> "synthesis-unavailable"
            request.text.length > TextToSpeech.getMaxSpeechInputLength() -> "text-too-long"
            !selectVoice(request) -> {
                if (request.voice.isNotEmpty()) "voice-unavailable" else "language-unavailable"
            }
            else -> null
        }
        if (error != null) return utterance.reply("error", error = error)

        tts.setSpeechRate(request.rate.coerce(0.1f, 10f, 1f))
        tts.setPitch(request.pitch.coerce(0.1f, 2f, 1f))
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, request.volume.coerce(0f, 1f, 1f))
        }
        val key = (nextKey++).toString()
        utterances[key] = utterance
        // An empty utterance still has to start and end in queue order.
        val queued = if (request.text.isBlank()) {
            tts.playSilentUtterance(0, TextToSpeech.QUEUE_ADD, key)
        } else {
            tts.speak(request.text, TextToSpeech.QUEUE_ADD, params, key)
        }
        if (queued != TextToSpeech.SUCCESS) {
            utterances.remove(key)
            utterance.reply("error", error = "synthesis-failed")
        }
    }

    /** The voice and language persist between utterances, so every request sets them. */
    private fun selectVoice(request: Request): Boolean = when {
        request.voice.isNotEmpty() -> {
            val voice = tts.voices.orEmpty().firstOrNull { it.name == request.voice }
            voice != null && tts.setVoice(voice) == TextToSpeech.SUCCESS
        }

        request.lang.isNotEmpty() ->
            tts.setLanguage(Locale.forLanguageTag(request.lang)) >= TextToSpeech.LANG_AVAILABLE

        else -> tts.defaultVoice?.let { tts.setVoice(it) == TextToSpeech.SUCCESS }
            ?: (tts.setLanguage(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE)
    }

    /** The page has already dropped its utterances. */
    private fun cancel() {
        utterances.clear()
        if (ready == true) tts.stop()
    }

    private fun voices(): String {
        val default = if (ready == true) tts.defaultVoice?.name else null
        val voices = if (ready == true) tts.voices.orEmpty() else emptySet()
        return buildJsonObject {
            put("type", "voices")
            putJsonArray("voices") {
                voices.sortedBy { it.name }.forEach { voice ->
                    addJsonObject {
                        put("name", voice.name)
                        put("lang", voice.locale.toLanguageTag())
                        put("localService", !voice.isNetworkConnectionRequired)
                        put("default", voice.name == default)
                    }
                }
            }
        }.toString()
    }

    private class Utterance(val proxy: JavaScriptReplyProxy, val id: Long) {
        fun reply(type: String, error: String? = null, charIndex: Int = 0, charLength: Int = 0) =
            proxy.postMessage(
                buildJsonObject {
                    put("type", type)
                    put("id", id)
                    error?.let { put("error", it) }
                    if (type == "boundary") {
                        put("charIndex", charIndex)
                        put("charLength", charLength)
                    }
                }.toString()
            )
    }

    /** Called on a binder thread. */
    private inner class Progress : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = post(utteranceId, "start", done = false)

        override fun onDone(utteranceId: String) = post(utteranceId, "end")

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String) =
            post(utteranceId, "error", error = "synthesis-failed")

        override fun onError(utteranceId: String, errorCode: Int) =
            post(utteranceId, "error", error = errorName(errorCode))

        // Only stop() and cancel() stop speech, and they have already told the page.
        override fun onStop(utteranceId: String, interrupted: Boolean) = Unit

        override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
            main.post {
                utterances[utteranceId]
                    ?.reply("boundary", charIndex = start, charLength = end - start)
            }
        }

        private fun post(key: String, type: String, done: Boolean = true, error: String? = null) {
            main.post {
                val utterance = if (done) utterances.remove(key) else utterances[key]
                utterance?.reply(type, error)
            }
        }
    }

    @Serializable
    private class Request(
        val type: String,
        val id: Long = 0,
        val text: String = "",
        val lang: String = "",
        val voice: String = "",
        val rate: Float = 1f,
        val pitch: Float = 1f,
        val volume: Float = 1f
    )

    private companion object {
        /** Must match the name speech_synthesis.js posts to. */
        const val OBJECT = "transmogrifierSpeech"

        val json = Json { ignoreUnknownKeys = true }

        fun Float.coerce(min: Float, max: Float, default: Float) =
            if (isFinite()) coerceIn(min, max) else default

        fun errorName(code: Int) = when (code) {
            TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> "network"
            TextToSpeech.ERROR_NOT_INSTALLED_YET -> "language-unavailable"
            TextToSpeech.ERROR_INVALID_REQUEST -> "invalid-argument"
            TextToSpeech.ERROR_OUTPUT -> "audio-hardware"
            else -> "synthesis-failed"
        }
    }
}
