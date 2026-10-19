package io.github.jambolo.transmogrifier

import android.content.Context
import io.github.jambolo.transmogrifier.agent.AgentEngine
import io.github.jambolo.transmogrifier.agent.GenerationEngine
import io.github.jambolo.transmogrifier.llm.LlmProvider
import io.github.jambolo.transmogrifier.llm.ProviderId
import io.github.jambolo.transmogrifier.llm.anthropic.AnthropicProvider
import io.github.jambolo.transmogrifier.llm.openai.OpenAiProvider
import io.github.jambolo.transmogrifier.project.FileProjectStore
import io.github.jambolo.transmogrifier.project.ProjectStore
import io.github.jambolo.transmogrifier.settings.DataStoreSettingsRepository
import io.github.jambolo.transmogrifier.settings.KeystoreCipher
import io.github.jambolo.transmogrifier.settings.SettingsRepository
import java.io.File

/** Manual dependency graph, created once in [TransmogrifierApplication]. */
class AppContainer(context: Context) {
    val settingsRepository: SettingsRepository =
        DataStoreSettingsRepository(File(context.filesDir, "settings.json"), KeystoreCipher())

    val projectStore: ProjectStore = FileProjectStore(projectsRoot(context))

    val providers: Map<ProviderId, LlmProvider> = listOf(
        AnthropicProvider({ settingsRepository.apiKey(ProviderId.ANTHROPIC) }),
        OpenAiProvider({ settingsRepository.apiKey(ProviderId.OPENAI) })
    ).filter { it.id.enabled }.associateBy { it.id }.also { registered ->
        check(registered.keys == ProviderId.enabledEntries.toSet()) {
            "Every enabled provider needs an adapter"
        }
    }

    val generationEngine: GenerationEngine =
        AgentEngine(projectStore, settingsRepository, providers)

    companion object {
        fun projectsRoot(context: Context) = File(context.filesDir, "projects")
    }
}
