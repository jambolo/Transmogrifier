package com.example.transmogrifier.settings

import com.example.transmogrifier.llm.Effort
import com.example.transmogrifier.llm.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class Settings(
    val defaultProvider: ProviderId = ProviderId.ANTHROPIC,
    val defaultModels: Map<ProviderId, String> = DEFAULT_MODELS,
    val effort: Effort = Effort.HIGH,
    val maxToolIterations: Int = 30,
    val maxOutputTokens: Int = 64_000,
    /** Last four characters of each saved key, for display. Keys never leave `settings` and `llm`. */
    val apiKeyHints: Map<ProviderId, String> = emptyMap()
) {
    fun defaultModel(provider: ProviderId) =
        defaultModels[provider] ?: DEFAULT_MODELS.getValue(provider)

    companion object {
        val DEFAULT_MODELS = mapOf(
            ProviderId.ANTHROPIC to "claude-opus-5-5",
            ProviderId.OPENAI to "gpt-6.1-sol"
        )
    }
}

interface SettingsRepository {
    val settings: Flow<Settings>

    suspend fun update(transform: (Settings) -> Settings)

    /** Saves [key], or removes the saved key when it is null. */
    suspend fun setApiKey(provider: ProviderId, key: String?)

    suspend fun apiKey(provider: ProviderId): String?
}

class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    private val keys = MutableStateFlow<Map<ProviderId, String>>(emptyMap())

    override val settings: Flow<Settings> = state

    override suspend fun update(transform: (Settings) -> Settings) = state.update(transform)

    override suspend fun setApiKey(provider: ProviderId, key: String?) {
        keys.update { if (key == null) it - provider else it + (provider to key) }
        state.update { settings ->
            val hints = settings.apiKeyHints - provider
            val hint = key?.takeLast(4)
            settings.copy(apiKeyHints = if (hint == null) hints else hints + (provider to hint))
        }
    }

    override suspend fun apiKey(provider: ProviderId): String? = keys.value[provider]
}
