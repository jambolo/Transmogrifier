package io.github.jambolo.transmogrifier.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.ConsoleMessage as WebConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.jambolo.transmogrifier.project.ProjectId
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A [WebView] that runs one project's generated site. The site is served from [siteDir] over https on its own
 * origin, so each project has its own storage. Every request outside that origin is blocked, and the console and
 * uncaught errors are collected in [console].
 *
 * The page gets no file or content access and no JavaScript interface. Uncaught errors and speech requests reach
 * the app through web message listeners limited to the site's origin.
 */
// The generated app is JavaScript; it is confined to its origin and process.
@SuppressLint("SetJavaScriptEnabled")
class SiteBrowser(context: Context, projectId: ProjectId, siteDir: File) {
    private val domain = "${projectId.value.lowercase()}.$ASSET_DOMAIN"
    val origin = "https://$domain"

    val webView = WebView(context)

    private val _console = MutableStateFlow<List<ConsoleMessage>>(emptyList())
    val console: StateFlow<List<ConsoleMessage>> = _console.asStateFlow()

    private val _dialog = MutableStateFlow<PageDialog?>(null)
    val dialog: StateFlow<PageDialog?> = _dialog.asStateFlow()

    /** The page's renderer is gone; the [webView] can't be used again. */
    private val _crashed = MutableStateFlow(false)
    val crashed: StateFlow<Boolean> = _crashed.asStateFlow()

    private val speech = SpeechBridge(context)

    private val assetLoader = WebViewAssetLoader.Builder()
        .setDomain(domain)
        .addPathHandler("/", SiteFiles(siteDir))
        .build()

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            // Games and timers play sounds without a tap first.
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.webViewClient = Client()
        webView.webChromeClient = ChromeClient()
        interceptServiceWorkers()
        installScripts()
    }

    fun load() = webView.loadUrl("$origin/")

    fun reload() {
        _console.value = emptyList()
        webView.reload()
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    fun stopSpeech() = speech.stop()

    fun destroy() {
        _dialog.value?.cancel()
        _dialog.value = null
        speech.shutdown()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun show(dialog: PageDialog) {
        _dialog.value?.cancel()
        _dialog.value = dialog
    }

    private fun log(message: ConsoleMessage) =
        _console.update { (it + message).takeLast(MAX_CONSOLE_MESSAGES) }

    private fun relative(url: String) = url.replace("$origin/", "")

    private fun intercept(url: Uri): WebResourceResponse = if (url.host == domain) {
        assetLoader.shouldInterceptRequest(url) ?: emptyResponse(404, "Not Found")
    } else {
        emptyResponse(403, "Forbidden")
    }

    private fun interceptServiceWorkers() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) ||
            !WebViewFeature.isFeatureSupported(
                WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST
            )
        ) {
            return
        }
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(
            object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest) =
                    intercept(request.url)
            }
        )
    }

    /**
     * Reports uncaught errors with their stacks and gives the page speech. Without the needed features, errors still
     * reach the console, but the page can't speak.
     */
    // Lint doesn't see the feature check because it's an early return.
    @SuppressLint("RequiresFeature")
    private fun installScripts() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            return
        }
        val rules = setOf(origin)
        val listener = WebViewCompat.WebMessageListener { _, message, _, _, _ ->
            onErrorReport(message.data)
        }
        WebViewCompat.addWebMessageListener(webView, REPORTER_OBJECT, rules, listener)
        WebViewCompat.addDocumentStartJavaScript(webView, REPORTER_SCRIPT, rules)
        speech.install(webView, rules)
    }

    private fun onErrorReport(data: String?) {
        val report = data?.let {
            runCatching { json.decodeFromString(ErrorReport.serializer(), it) }.getOrNull()
        } ?: return
        log(
            ConsoleMessage(
                ConsoleMessage.Level.ERROR,
                report.message.take(MAX_REPORT_CHARS),
                relative(report.source),
                report.line,
                relative(report.stack.take(MAX_REPORT_CHARS))
            )
        )
    }

    // onRenderProcessGone is implemented below; lint misses it in inner classes.
    @SuppressLint("MissingOnRenderProcessGone")
    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
            intercept(request.url)

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = speech.stop()

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.scheme == "https" && url.host == domain) return false
            if (request.isForMainFrame && url.scheme in EXTERNAL_SCHEMES) {
                show(PageDialog.ExternalLink(url))
            }
            return true
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            // Subresource failures are already in the console; a missing page is not.
            if (request.isForMainFrame) {
                val path = relative(request.url.toString()).ifEmpty { "index.html" }
                log(
                    ConsoleMessage(
                        ConsoleMessage.Level.ERROR,
                        "Could not load $path: ${errorResponse.statusCode} " +
                            errorResponse.reasonPhrase,
                        "",
                        0
                    )
                )
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            log(
                ConsoleMessage(
                    ConsoleMessage.Level.ERROR,
                    "The page stopped: it crashed or ran out of memory.",
                    "",
                    0
                )
            )
            _crashed.value = true
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {
        override fun onConsoleMessage(message: WebConsoleMessage): Boolean {
            val level = when (message.messageLevel()) {
                WebConsoleMessage.MessageLevel.ERROR -> ConsoleMessage.Level.ERROR
                WebConsoleMessage.MessageLevel.WARNING -> ConsoleMessage.Level.WARNING
                else -> ConsoleMessage.Level.LOG
            }
            log(
                ConsoleMessage(
                    level,
                    message.message(),
                    relative(message.sourceId()),
                    message.lineNumber()
                )
            )
            return true
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult) =
            true.also { show(PageDialog.Alert(message, result)) }

        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult) =
            true.also { show(PageDialog.Confirm(message, result)) }

        override fun onJsPrompt(
            view: WebView,
            url: String,
            message: String,
            defaultValue: String?,
            result: JsPromptResult
        ) = true.also { show(PageDialog.Prompt(message, defaultValue.orEmpty(), result)) }

        override fun onJsBeforeUnload(
            view: WebView,
            url: String,
            message: String,
            result: JsResult
        ) = true.also { result.confirm() }

        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) = callback.invoke(origin, false, false)
    }

    /** Serves site files with types the module loader accepts, and the policy that keeps the page offline. */
    private class SiteFiles(siteDir: File) : WebViewAssetLoader.PathHandler {
        private val root = siteDir.canonicalFile

        override fun handle(path: String): WebResourceResponse? {
            val name = if (path.isEmpty() || path.endsWith("/")) "${path}index.html" else path
            val file = File(root, name).canonicalFile
            if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return null
            val type = MIME_TYPES[file.extension.lowercase()] ?: "application/octet-stream"
            return WebResourceResponse(type, "utf-8", 200, "OK", HEADERS, file.inputStream())
        }
    }

    @Serializable
    private class ErrorReport(
        val message: String,
        val source: String = "",
        val line: Int = 0,
        val stack: String = ""
    )

    private companion object {
        /** Reserved for WebViewAssetLoader, so it never resolves to a real server. */
        const val ASSET_DOMAIN = "appassets.androidplatform.net"
        const val MAX_CONSOLE_MESSAGES = 500
        const val MAX_REPORT_CHARS = 4_000
        const val REPORTER_OBJECT = "transmogrifierErrors"

        val EXTERNAL_SCHEMES = setOf("http", "https", "mailto", "tel")
        val json = Json { ignoreUnknownKeys = true }

        val MIME_TYPES = mapOf(
            "html" to "text/html",
            "css" to "text/css",
            "js" to "text/javascript",
            "mjs" to "text/javascript",
            "json" to "application/json",
            "svg" to "image/svg+xml",
            "txt" to "text/plain",
            "md" to "text/markdown"
        )

        // Network access stays off even for a page that tries fetch, WebSocket, or a form post to another host.
        val HEADERS = mapOf(
            "Content-Security-Policy" to
                "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; " +
                "form-action 'self'; base-uri 'self'",
            "Cache-Control" to "no-store"
        )

        fun emptyResponse(status: Int, reason: String) = WebResourceResponse(
            "text/plain",
            "utf-8",
            status,
            reason,
            HEADERS,
            ByteArrayInputStream(ByteArray(0))
        )

        // Handling the events suppresses Chromium's own "Uncaught" console line, so each error is reported once.
        val REPORTER_SCRIPT = """
            (() => {
              const report = (message, source, line, stack) => {
                const channel = window.$REPORTER_OBJECT;
                if (!channel) return false;
                channel.postMessage(JSON.stringify({
                  message: String(message),
                  source: String(source || ''),
                  line: Number(line) || 0,
                  stack: String(stack || '')
                }));
                return true;
              };
              window.addEventListener('error', (event) => {
                if (!(event instanceof ErrorEvent)) return;
                const stack = event.error && event.error.stack;
                if (report(event.message, event.filename, event.lineno, stack)) event.preventDefault();
              });
              window.addEventListener('unhandledrejection', (event) => {
                const reason = event.reason;
                const text = reason instanceof Error ? reason.name + ': ' + reason.message : String(reason);
                if (report('Uncaught (in promise) ' + text, '', 0, reason && reason.stack)) {
                  event.preventDefault();
                }
              });
            })();
        """.trimIndent()
    }
}
