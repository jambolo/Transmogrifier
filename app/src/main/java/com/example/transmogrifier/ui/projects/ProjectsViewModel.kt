package com.example.transmogrifier.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.transmogrifier.project.ProjectId
import com.example.transmogrifier.project.ProjectStore
import com.example.transmogrifier.project.ProjectSummary
import com.example.transmogrifier.settings.SettingsRepository
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
            val provider = settings.defaultProvider
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
