package io.github.jambolo.transmogrifier.runtime

import android.net.Uri
import android.webkit.JsPromptResult
import android.webkit.JsResult

/** A dialog the page asked for; the page's script waits until it is answered. */
sealed interface PageDialog {
    data class Alert(val message: String, val result: JsResult) : PageDialog

    data class Confirm(val message: String, val result: JsResult) : PageDialog

    data class Prompt(val message: String, val defaultValue: String, val result: JsPromptResult) :
        PageDialog

    /** A navigation away from the app, which opens in the system browser only if the user agrees. */
    data class ExternalLink(val uri: Uri) : PageDialog

    /** Answers the page's dialog as if the user dismissed it. */
    fun cancel() {
        when (this) {
            is Alert -> result.cancel()
            is Confirm -> result.cancel()
            is Prompt -> result.cancel()
            is ExternalLink -> Unit
        }
    }
}
