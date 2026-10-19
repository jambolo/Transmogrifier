package io.github.jambolo.transmogrifier.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Scripted provider for UI work and tests: [script] returns the events for each turn, emitted [delayMillis] apart. */
class FakeLlmProvider(
    override val id: ProviderId,
    private val models: List<ModelInfo> = listOf(ModelInfo("fake-model", "Fake model")),
    private val delayMillis: Long = 0,
    private val script: (TurnRequest) -> List<TurnEvent>
) : LlmProvider {
    override suspend fun listModels(): List<ModelInfo> = models

    override fun runTurn(request: TurnRequest): Flow<TurnEvent> = flow {
        for (event in script(request)) {
            if (delayMillis > 0) delay(delayMillis)
            emit(event)
        }
    }
}
