package com.example.transmogrifier.ui.workspace

import androidx.lifecycle.SavedStateHandle
import com.example.transmogrifier.agent.FakeGenerationEngine
import com.example.transmogrifier.llm.ProviderId
import com.example.transmogrifier.project.FakeProjectStore
import com.example.transmogrifier.project.NoticeKind
import com.example.transmogrifier.project.TranscriptEntry
import com.example.transmogrifier.ui.RunnerResults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: FakeProjectStore

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = FakeProjectStore(temp.root)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.viewModel(
        handle: SavedStateHandle = SavedStateHandle()
    ): WorkspaceViewModel {
        val projectId = store.create("Test", ProviderId.ANTHROPIC, "model").id
        val viewModel = WorkspaceViewModel(projectId, handle, store, FakeGenerationEngine(store))
        backgroundScope.launch { viewModel.state.collect {} }
        return viewModel
    }

    @Test
    fun generationStreamsIntoLiveTurnThenTranscript() = runTest {
        val viewModel = viewModel()
        viewModel.send("A counter")

        val streaming = viewModel.state.first { it.live.text.isNotEmpty() }
        assertTrue(streaming.generating)

        val done = viewModel.state.first { !it.generating && it.files.size == 3 }
        assertEquals(TranscriptEntry.Prompt("A counter"), done.transcript.first())
        assertEquals(3, done.transcript.count { it is TranscriptEntry.Tool })
        assertTrue(done.transcript.last() is TranscriptEntry.Usage)
        assertEquals(LiveTurn(), done.live)
    }

    @Test
    fun stopRecordsNotice() = runTest {
        val viewModel = viewModel()
        viewModel.send("A counter")
        viewModel.state.first { it.live.text.isNotEmpty() }
        viewModel.stop()

        val stopped = viewModel.state.first { !it.generating }
        assertEquals(TranscriptEntry.Notice(NoticeKind.STOPPED), stopped.transcript.last())
    }

    @Test
    fun runnerErrorsStartFixGeneration() = runTest {
        val handle = SavedStateHandle()
        val viewModel = viewModel(handle)
        handle[RunnerResults.CONSOLE_ERRORS] = "boom (app.js:1)"
        assertEquals(
            "boom (app.js:1)",
            viewModel.state.first {
                it.consoleErrors != null
            }.consoleErrors
        )

        handle[RunnerResults.FIX_ERRORS] = "boom (app.js:1)"
        val done = viewModel.state.first { !it.generating && it.transcript.isNotEmpty() }
        assertEquals(
            TranscriptEntry.Prompt("boom (app.js:1)", fixErrors = true),
            done.transcript.first()
        )
        assertEquals(null, done.consoleErrors)
        assertEquals(null, handle.get<String>(RunnerResults.FIX_ERRORS))
    }
}
