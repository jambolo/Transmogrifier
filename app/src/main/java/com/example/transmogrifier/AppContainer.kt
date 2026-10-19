package com.example.transmogrifier

import android.content.Context
import com.example.transmogrifier.agent.FakeGenerationEngine
import com.example.transmogrifier.agent.GenerationEngine
import com.example.transmogrifier.llm.FakeLlmProvider
import com.example.transmogrifier.llm.LlmProvider
import com.example.transmogrifier.llm.ModelInfo
import com.example.transmogrifier.llm.ProviderId
import com.example.transmogrifier.project.FakeProjectStore
import com.example.transmogrifier.project.ProjectStore
import com.example.transmogrifier.settings.FakeSettingsRepository
import com.example.transmogrifier.settings.SettingsRepository
import java.io.File

/** Manual dependency graph, created once in [TransmogrifierApplication]. Phase 2 wires fakes only. */
class AppContainer(context: Context) {
    val settingsRepository: SettingsRepository = FakeSettingsRepository()

    val projectStore: ProjectStore = FakeProjectStore(File(context.filesDir, "projects"))

    val providers: Map<ProviderId, LlmProvider> = mapOf(
        ProviderId.ANTHROPIC to FakeLlmProvider(
            ProviderId.ANTHROPIC,
            models = listOf(
                ModelInfo("claude-opus-5-5", "Claude Opus 5.5"),
                ModelInfo("claude-sonnet-5-5", "Claude Sonnet 5.5"),
                ModelInfo("claude-haiku-5-5", "Claude Haiku 5.5")
            ),
            script = { emptyList() }
        ),
        ProviderId.OPENAI to FakeLlmProvider(
            ProviderId.OPENAI,
            models = listOf(ModelInfo("gpt-5-codex", "GPT-5 Codex")),
            script = { emptyList() }
        )
    )

    val generationEngine: GenerationEngine = FakeGenerationEngine(projectStore)
}
