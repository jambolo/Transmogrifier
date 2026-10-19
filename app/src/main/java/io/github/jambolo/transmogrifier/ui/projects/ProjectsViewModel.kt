package io.github.jambolo.transmogrifier.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jambolo.transmogrifier.project.ProjectId
import io.github.jambolo.transmogrifier.project.ProjectStore
import io.github.jambolo.transmogrifier.project.ProjectSummary
import io.github.jambolo.transmogrifier.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProjectsViewModel(
    private val store: ProjectStore,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    val projects: StateFlow<List<ProjectSummary>> = store.projects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
}
