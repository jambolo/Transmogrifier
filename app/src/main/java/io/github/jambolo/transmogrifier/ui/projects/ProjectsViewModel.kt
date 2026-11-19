package io.github.jambolo.transmogrifier.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jambolo.transmogrifier.project.ArchiveContents
import io.github.jambolo.transmogrifier.project.ImportDefaults
import io.github.jambolo.transmogrifier.project.ProjectArchive
import io.github.jambolo.transmogrifier.project.ProjectId
import io.github.jambolo.transmogrifier.project.ProjectStore
import io.github.jambolo.transmogrifier.project.ProjectSummary
import io.github.jambolo.transmogrifier.settings.SettingsRepository
import java.io.File
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A failure to export or import, shown once. */
sealed interface ArchiveMessage {
    data class ExportFailed(val detail: String) : ArchiveMessage

    data class ImportFailed(val detail: String) : ArchiveMessage
}

class ProjectsViewModel(
    private val store: ProjectStore,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    private val archive = ProjectArchive(store)

    val projects: StateFlow<List<ProjectSummary>> = store.projects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<ArchiveMessage?>(null)
    val message: StateFlow<ArchiveMessage?> = _message.asStateFlow()

    /** A backup with a conversation, waiting for the user to choose whether to keep it. */
    private val _pendingImport = MutableStateFlow<ArchiveContents?>(null)
    val pendingImport: StateFlow<ArchiveContents?> = _pendingImport.asStateFlow()

    fun create(name: String, onCreated: (ProjectId) -> Unit) {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val provider = settings.newAppProvider
            onCreated(store.create(name, provider, settings.defaultModel(provider)).id)
        }
    }

    fun rename(id: ProjectId, name: String) {
        viewModelScope.launch { store.rename(id, name) }
    }

    fun delete(id: ProjectId) {
        viewModelScope.launch { store.delete(id) }
    }

    /**
     * Writes the zip to [file], replacing earlier exports in its folder, and passes it to [onExported] to share.
     */
    fun export(id: ProjectId, backup: Boolean, file: File, onExported: (File) -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val dir = file.parentFile!!
                    dir.deleteRecursively()
                    dir.mkdirs()
                }
                withContext(Dispatchers.IO) { file.outputStream() }.use {
                    if (backup) archive.exportBackup(id, it) else archive.exportSite(id, it)
                }
                onExported(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = ArchiveMessage.ExportFailed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Reads the zip from [open]; a site-only zip is named by [name], which may read the file's name from storage. A
     * backup with a conversation waits in [pendingImport]; anything else is imported at once and passed to
     * [onImported].
     */
    fun import(open: () -> InputStream?, name: () -> String, onImported: (ProjectId) -> Unit) {
        viewModelScope.launch {
            val contents = failingImport {
                val (input, fallbackName) = withContext(Dispatchers.IO) { open() to name() }
                (input ?: error("The file couldn't be opened")).use {
                    archive.read(it, fallbackName)
                }
            } ?: return@launch
            if (contents.hasConversation) {
                _pendingImport.value = contents
            } else {
                finishImport(contents, withConversation = false, onImported)
            }
        }
    }

    fun confirmImport(withConversation: Boolean, onImported: (ProjectId) -> Unit) {
        val contents = _pendingImport.value ?: return
        _pendingImport.value = null
        viewModelScope.launch { finishImport(contents, withConversation, onImported) }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    fun messageShown() {
        _message.value = null
    }

    private suspend fun finishImport(
        contents: ArchiveContents,
        withConversation: Boolean,
        onImported: (ProjectId) -> Unit
    ) {
        val settings = settingsRepository.settings.first()
        val provider = settings.newAppProvider
        val defaults = ImportDefaults(provider, settings.defaultModel(provider))
        failingImport { archive.import(contents, defaults, withConversation) }
            ?.let { onImported(it.id) }
    }

    /** Runs [block], reporting a failure as [ArchiveMessage.ImportFailed] and returning null. */
    private suspend fun <T> failingImport(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _message.value = ArchiveMessage.ImportFailed(e.message ?: e.javaClass.simpleName)
        null
    }
}
