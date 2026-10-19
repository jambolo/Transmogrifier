package com.example.transmogrifier.ui.runner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.transmogrifier.project.ProjectId
import com.example.transmogrifier.project.ProjectStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class ConsoleMessage(
    val level: Level,
    val message: String,
    val source: String,
    val line: Int
) {
    enum class Level { LOG, WARNING, ERROR }
}

/** The errors in a form suitable for the LLM, or null when there are none. */
fun List<ConsoleMessage>.errorLog(): String? = filter { it.level == ConsoleMessage.Level.ERROR }
    .takeIf { it.isNotEmpty() }
    ?.joinToString("\n") { "${it.message} (${it.source}:${it.line})" }

/** Phase 2: the console is canned. Phase 4 feeds it from the WebView in the `:runner` process. */
class RunnerViewModel(projectId: ProjectId, store: ProjectStore) : ViewModel() {
    val projectName: StateFlow<String> = store.project(projectId)
        .map { it?.name.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val projectMissing: StateFlow<Boolean> = store.project(projectId)
        .map { it == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _console = MutableStateFlow(fakeConsole())
    val console: StateFlow<List<ConsoleMessage>> = _console.asStateFlow()

    fun reload() {
        _console.value = fakeConsole()
    }

    private fun fakeConsole() = listOf(
        ConsoleMessage(ConsoleMessage.Level.LOG, "App started", "js/app.js", 1),
        ConsoleMessage(
            ConsoleMessage.Level.ERROR,
            "Uncaught ReferenceError: total is not defined",
            "js/app.js",
            9
        )
    )
}
