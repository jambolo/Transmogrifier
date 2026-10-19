package com.example.transmogrifier.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.transmogrifier.llm.Effort
import com.example.transmogrifier.llm.LlmProvider
import com.example.transmogrifier.llm.ModelInfo
import com.example.transmogrifier.llm.ProviderId
import com.example.transmogrifier.settings.Settings
import com.example.transmogrifier.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface KeyTest {
    data object Running : KeyTest

    data class Passed(val modelCount: Int) : KeyTest

    data class Failed(val message: String) : KeyTest
}

data class SettingsUiState(
    val settings: Settings = Settings(),
    val keyTests: Map<ProviderId, KeyTest> = emptyMap(),
    /** Models returned by the last successful key test, per provider. */
    val models: Map<ProviderId, List<ModelInfo>> = emptyMap()
)

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val providers: Map<ProviderId, LlmProvider>
) : ViewModel() {
    private val keyTests = MutableStateFlow<Map<ProviderId, KeyTest>>(emptyMap())
    private val models = MutableStateFlow<Map<ProviderId, List<ModelInfo>>>(emptyMap())

    val state: StateFlow<SettingsUiState> = combine(
        repository.settings,
        keyTests,
        models,
        ::SettingsUiState
    )
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun saveKey(provider: ProviderId, key: String) {
        viewModelScope.launch {
            repository.setApiKey(provider, key.trim())
            keyTests.update { it - provider }
        }
    }

    fun clearKey(provider: ProviderId) {
        viewModelScope.launch {
            repository.setApiKey(provider, null)
            keyTests.update { it - provider }
        }
    }

    fun testKey(provider: ProviderId) {
        viewModelScope.launch {
            keyTests.update { it + (provider to KeyTest.Running) }
            val result = try {
                val list = providers.getValue(provider).listModels()
                models.update { it + (provider to list) }
                KeyTest.Passed(list.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KeyTest.Failed(e.message ?: e.javaClass.simpleName)
            }
            keyTests.update { it + (provider to result) }
        }
    }

    fun setDefaultProvider(provider: ProviderId) = update { it.copy(defaultProvider = provider) }

    fun setDefaultModel(provider: ProviderId, model: String) =
        update { it.copy(defaultModels = it.defaultModels + (provider to model)) }

    fun setEffort(effort: Effort) = update { it.copy(effort = effort) }

    fun setMaxToolIterations(value: Int) = update { it.copy(maxToolIterations = value) }

    fun setMaxOutputTokens(value: Int) = update { it.copy(maxOutputTokens = value) }

    private fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { repository.update(transform) }
    }
}
