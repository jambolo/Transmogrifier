package io.github.jambolo.transmogrifier.settings

import io.github.jambolo.transmogrifier.llm.Effort
import io.github.jambolo.transmogrifier.llm.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class Settings(
    val defaultProvider: ProviderId = ProviderId.ANTHROPIC,
    /** The user's choices; providers without one use [ProviderId.defaultModel]. */
    val defaultModels: Map<ProviderId, String> = emptyMap(),
    val effort: Effort = Effort.HIGH,
    val maxToolIterations: Int = 30,
    val maxOutputTokens: Int = 64_000,
    /** The user's standing instructions for every app; blank for none. */
    val appInstructions: String = "",
    /** Last four characters of each saved key, for display. Keys never leave `settings` and `llm`. */
    val apiKeyHints: Map<ProviderId, String> = emptyMap()
) {
    /** The provider for new apps: [defaultProvider], or the first enabled one if it has been disabled. */
    val newAppProvider: ProviderId
        get() = defaultProvider.takeIf { it.enabled } ?: ProviderId.enabledEntries.first()

    fun defaultModel(provider: ProviderId) = defaultModels[provider] ?: provider.defaultModel

    companion object {
        const val MAX_APP_INSTRUCTIONS_LENGTH = 4_000
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
