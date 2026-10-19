package com.example.transmogrifier.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.transmogrifier.R
import com.example.transmogrifier.llm.Effort
import com.example.transmogrifier.llm.ProviderId

@Composable
fun providerLabel(provider: ProviderId): String = stringResource(
    when (provider) {
        ProviderId.ANTHROPIC -> R.string.provider_anthropic
        ProviderId.OPENAI -> R.string.provider_openai
    }
)

@Composable
fun effortLabel(effort: Effort): String = stringResource(
    when (effort) {
        Effort.LOW -> R.string.effort_low
        Effort.MEDIUM -> R.string.effort_medium
        Effort.HIGH -> R.string.effort_high
        Effort.XHIGH -> R.string.effort_xhigh
        Effort.MAX -> R.string.effort_max
    }
)

/** Activity-log line for a tool call, in progress ([running]) or finished. */
@Composable
fun toolLabel(name: String, path: String?, running: Boolean): String {
    // A file tool's path streams in after its name.
    val awaitingPath = running && path == null && name != "list_files"
    if (awaitingPath) return stringResource(R.string.tool_preparing)
    val target = path.orEmpty()
    return when (name) {
        "list_files" ->
            stringResource(if (running) R.string.tool_listing else R.string.tool_listed)

        "read_file" ->
            stringResource(if (running) R.string.tool_reading else R.string.tool_read, target)

        "write_file" ->
            stringResource(if (running) R.string.tool_writing else R.string.tool_wrote, target)

        "edit_file" ->
            stringResource(if (running) R.string.tool_editing else R.string.tool_edited, target)

        "delete_file" ->
            stringResource(if (running) R.string.tool_deleting else R.string.tool_deleted, target)

        else -> listOfNotNull(name, path).joinToString(" ")
    }
}
