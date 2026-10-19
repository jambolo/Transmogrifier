package io.github.jambolo.transmogrifier.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jambolo.transmogrifier.llm.Effort
import io.github.jambolo.transmogrifier.llm.LlmException
import io.github.jambolo.transmogrifier.llm.LlmProvider
import io.github.jambolo.transmogrifier.llm.ModelInfo
import io.github.jambolo.transmogrifier.llm.ProviderId
import io.github.jambolo.transmogrifier.settings.Settings
import io.github.jambolo.transmogrifier.settings.SettingsRepository
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

    data object Rejected : KeyTest

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
            } catch (e: LlmException.InvalidKey) {
                KeyTest.Rejected
            } catch (e: LlmException) {
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
