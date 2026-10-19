package io.github.jambolo.transmogrifier.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import io.github.jambolo.transmogrifier.llm.Effort
import io.github.jambolo.transmogrifier.llm.ProviderId
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** On-disk form of [Settings]; API keys are stored only as [SecretCipher] output. */
@Serializable
internal data class StoredSettings(
    val defaultProvider: ProviderId = ProviderId.ANTHROPIC,
    val defaultModels: Map<ProviderId, String> = emptyMap(),
    val effort: Effort = Effort.HIGH,
    val maxToolIterations: Int = 30,
    val maxOutputTokens: Int = 64_000,
    val encryptedKeys: Map<ProviderId, String> = emptyMap(),
    val keyHints: Map<ProviderId, String> = emptyMap()
) {
    fun toSettings() = Settings(
        defaultProvider,
        defaultModels,
        effort,
        maxToolIterations,
        maxOutputTokens,
        keyHints
    )
}

private object StoredSettingsSerializer : Serializer<StoredSettings> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue = StoredSettings()

    override suspend fun readFrom(input: InputStream): StoredSettings = try {
        json.decodeFromString(StoredSettings.serializer(), input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable settings", e)
    }

    override suspend fun writeTo(t: StoredSettings, output: OutputStream) =
        output.write(json.encodeToString(StoredSettings.serializer(), t).encodeToByteArray())
}

class DataStoreSettingsRepository internal constructor(
    private val store: DataStore<StoredSettings>,
    private val cipher: SecretCipher
) : SettingsRepository {
    constructor(file: File, cipher: SecretCipher) : this(
        DataStoreFactory.create(StoredSettingsSerializer, produceFile = { file }),
        cipher
    )

    override val settings: Flow<Settings> = store.data.map { it.toSettings() }

    override suspend fun update(transform: (Settings) -> Settings) {
        store.updateData { stored ->
            val new = transform(stored.toSettings())
            stored.copy(
                defaultProvider = new.defaultProvider,
                defaultModels = new.defaultModels,
                effort = new.effort,
                maxToolIterations = new.maxToolIterations,
                maxOutputTokens = new.maxOutputTokens
            )
        }
    }

    override suspend fun setApiKey(provider: ProviderId, key: String?) {
        val encrypted = key?.let(cipher::encrypt)
        store.updateData { stored ->
            val keys = stored.encryptedKeys - provider
            val hints = stored.keyHints - provider
            if (encrypted == null) {
                stored.copy(encryptedKeys = keys, keyHints = hints)
            } else {
                stored.copy(
                    encryptedKeys = keys + (provider to encrypted),
                    keyHints = hints + (provider to key.takeLast(4))
                )
            }
        }
    }

    override suspend fun apiKey(provider: ProviderId): String? {
        val encrypted = store.data.first().encryptedKeys[provider] ?: return null
        return try {
            cipher.decrypt(encrypted)
        } catch (_: GeneralSecurityException) {
            // The Keystore key is gone (e.g. lock screen reset); the user has to enter the key again.
            setApiKey(provider, null)
            null
        }
    }
}
