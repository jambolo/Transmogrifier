package io.github.jambolo.transmogrifier.runtime

/** One line of the generated app's console. [source] is relative to the site root when it is a site file. */
data class ConsoleMessage(
    val level: Level,
    val message: String,
    val source: String,
    val line: Int,
    val stack: String? = null
) {
    enum class Level { LOG, WARNING, ERROR }
}

private const val MAX_LOGGED_ERRORS = 20
private const val MAX_LOG_CHARS = 16_000

/** The most recent errors in a form suitable for the LLM, or null when there are none. */
fun List<ConsoleMessage>.errorLog(): String? = filter { it.level == ConsoleMessage.Level.ERROR }
    .takeLast(MAX_LOGGED_ERRORS)
    .takeIf { it.isNotEmpty() }
    ?.joinToString("\n") { error ->
        val location = if (error.source.isEmpty()) "" else " (${error.source}:${error.line})"
        val stack = error.stack?.takeIf { it.isNotBlank() }?.prependIndent("    ")
        "${error.message}$location" + stack?.let { "\n$it" }.orEmpty()
    }
    ?.takeLast(MAX_LOG_CHARS)
