package io.github.jambolo.transmogrifier.ui.settings

import io.github.jambolo.transmogrifier.llm.ProviderId
import io.github.jambolo.transmogrifier.settings.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val repository = FakeSettingsRepository()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun firstKeySelectsItsProvider() = runTest {
        val viewModel = SettingsViewModel(repository, emptyMap())
        viewModel.saveKey(ProviderId.OPENAI, "sk-test")
        assertEquals(ProviderId.OPENAI, repository.settings.first().newAppProvider)
    }

    @Test
    fun laterKeyKeepsTheChosenProvider() = runTest {
        val viewModel = SettingsViewModel(repository, emptyMap())
        viewModel.saveKey(ProviderId.ANTHROPIC, "sk-ant-test")
        viewModel.saveKey(ProviderId.OPENAI, "sk-test")
        assertEquals(ProviderId.ANTHROPIC, repository.settings.first().newAppProvider)
    }
}
