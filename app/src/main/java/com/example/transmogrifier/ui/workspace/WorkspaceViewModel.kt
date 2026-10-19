package com.example.transmogrifier.ui.workspace

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.transmogrifier.agent.GenerationEngine
import com.example.transmogrifier.agent.GenerationEvent
import com.example.transmogrifier.project.FileEntry
import com.example.transmogrifier.project.NoticeKind
import com.example.transmogrifier.project.Project
import com.example.transmogrifier.project.ProjectId
import com.example.transmogrifier.project.ProjectStore
import com.example.transmogrifier.project.TranscriptEntry
import com.example.transmogrifier.project.Workspace
import com.example.transmogrifier.project.WorkspaceResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The in-progress part of a generation that is not yet in the transcript. */
data class LiveTurn(
    val text: String = "",
    val tool: GenerationEvent.ToolRunning? = null,
    val retry: GenerationEvent.Retrying? = null
)

data class OpenFile(val path: String, val content: String?)

data class WorkspaceUiState(
    val project: Project? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
    val generating: Boolean = false,
    val live: LiveTurn = LiveTurn(),
    val files: List<FileEntry> = emptyList(),
    val openFile: OpenFile? = null,
    val consoleErrors: String? = null,
    /** The project no longer exists (deleted, or lost by the in-memory fake on process death). */
    val projectMissing: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModel(
    private val projectId: ProjectId,
    private val savedStateHandle: SavedStateHandle,
    private val store: ProjectStore,
    private val engine: GenerationEngine
) : ViewModel() {
    private val generating = MutableStateFlow(false)
    private val live = MutableStateFlow(LiveTurn())
    private val files = MutableStateFlow<List<FileEntry>>(emptyList())
    private var generation: Job? = null

    private val openFile = combine(
        savedStateHandle.getStateFlow<String?>(OPEN_FILE, null),
        files
    ) { path, _ -> path }.mapLatest { path ->
        path?.let { OpenFile(it, (workspace()?.read(it) as? WorkspaceResult.Ok)?.value) }
    }

    val state: StateFlow<WorkspaceUiState> = combine(
        combine(store.project(projectId), store.transcript(projectId), ::Pair),
        combine(generating, live, ::Pair),
        files,
        openFile,
        savedStateHandle.getStateFlow<String?>(CONSOLE_ERRORS, null)
    ) { (project, transcript), (generating, live), files, openFile, consoleErrors ->
        WorkspaceUiState(
            project,
            transcript,
            generating,
            live,
            files,
            openFile,
            consoleErrors,
            projectMissing = project == null
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkspaceUiState())

    init {
        refreshFiles()
    }

    fun send(prompt: String) = generate(prompt, fixErrors = false)

    /** Continues a stopped or failed generation from its pending input. */
    fun resume() = generate(null, fixErrors = false)

    fun fixErrors() {
        savedStateHandle.get<String>(CONSOLE_ERRORS)?.let(::fix)
    }

    /** Keeps the run's [errorLog] for **Fix errors**, or starts the fix when the user sent them from the runner. */
    fun onRunFinished(errorLog: String?, fixErrors: Boolean) {
        savedStateHandle[CONSOLE_ERRORS] = errorLog
        if (fixErrors && errorLog != null) fix(errorLog)
    }

    fun stop() {
        generation?.cancel()
    }

    fun openFile(path: String?) {
        savedStateHandle[OPEN_FILE] = path
    }

    private fun fix(log: String) {
        if (generating.value) return
        savedStateHandle[CONSOLE_ERRORS] = null
        generate(log, fixErrors = true)
    }

    private fun generate(prompt: String?, fixErrors: Boolean) {
        if (generating.value) return
        generating.value = true
        generation = viewModelScope.launch {
            try {
                engine.generate(projectId, prompt, fixErrors).collect { event ->
                    when (event) {
                        is GenerationEvent.TextDelta ->
                            live.update { it.copy(text = it.text + event.text, retry = null) }

                        is GenerationEvent.ToolRunning -> live.update {
                            it.copy(tool = event, retry = null)
                        }

                        GenerationEvent.TurnPersisted -> {
                            live.value = LiveTurn()
                            refreshFiles()
                        }

                        // The failed attempt's partial output is discarded.
                        is GenerationEvent.Retrying -> live.value = LiveTurn(retry = event)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.appendTranscript(
                    projectId,
                    TranscriptEntry.Notice(NoticeKind.FAILED, e.message)
                )
            } finally {
                generating.value = false
                live.value = LiveTurn()
                refreshFiles()
            }
        }
    }

    private fun refreshFiles() {
        viewModelScope.launch { files.value = workspace()?.listFiles().orEmpty() }
    }

    private suspend fun workspace(): Workspace? = try {
        store.workspace(projectId)
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val OPEN_FILE = "openFile"

        /** Error log from the last run, or null when it had no errors. */
        const val CONSOLE_ERRORS = "consoleErrors"
    }
}
