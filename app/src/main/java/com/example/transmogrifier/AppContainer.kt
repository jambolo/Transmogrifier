package com.example.transmogrifier

import android.content.Context
import com.example.transmogrifier.agent.AgentEngine
import com.example.transmogrifier.agent.GenerationEngine
import com.example.transmogrifier.llm.LlmProvider
import com.example.transmogrifier.llm.ProviderId
import com.example.transmogrifier.llm.anthropic.AnthropicProvider
import com.example.transmogrifier.llm.openai.OpenAiProvider
import com.example.transmogrifier.project.FileProjectStore
import com.example.transmogrifier.project.ProjectStore
import com.example.transmogrifier.settings.DataStoreSettingsRepository
import com.example.transmogrifier.settings.KeystoreCipher
import com.example.transmogrifier.settings.SettingsRepository
import java.io.File

/** Manual dependency graph, created once in [TransmogrifierApplication]. */
class AppContainer(context: Context) {
    val settingsRepository: SettingsRepository =
        DataStoreSettingsRepository(File(context.filesDir, "settings.json"), KeystoreCipher())

    val projectStore: ProjectStore = FileProjectStore(projectsRoot(context))

    val providers: Map<ProviderId, LlmProvider> = listOf(
        AnthropicProvider({ settingsRepository.apiKey(ProviderId.ANTHROPIC) }),
        OpenAiProvider({ settingsRepository.apiKey(ProviderId.OPENAI) })
    ).associateBy { it.id }.also { registered ->
        check(registered.keys == ProviderId.entries.toSet()) { "Every provider needs an adapter" }
    }

    val generationEngine: GenerationEngine =
        AgentEngine(projectStore, settingsRepository, providers)

    companion object {
        fun projectsRoot(context: Context) = File(context.filesDir, "projects")
    }
}
