package io.github.jambolo.transmogrifier.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jambolo.transmogrifier.llm.ProviderId
import io.github.jambolo.transmogrifier.project.FileEntry
import io.github.jambolo.transmogrifier.project.Project
import io.github.jambolo.transmogrifier.project.ProjectId
import io.github.jambolo.transmogrifier.project.ProjectSummary
import io.github.jambolo.transmogrifier.project.TranscriptEntry
import io.github.jambolo.transmogrifier.ui.projects.ProjectsScreen
import io.github.jambolo.transmogrifier.ui.settings.SettingsScreen
import io.github.jambolo.transmogrifier.ui.settings.SettingsUiState
import io.github.jambolo.transmogrifier.ui.theme.TransmogrifierTheme
import io.github.jambolo.transmogrifier.ui.workspace.WorkspaceScreen
import io.github.jambolo.transmogrifier.ui.workspace.WorkspaceUiState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrandingTest {
    @get:Rule
    val compose = createComposeRule()

    private val id = ProjectId("brand-preview")
    private val app = ProjectSummary(
        id,
        "Game night",
        ProviderId.ANTHROPIC,
        System.currentTimeMillis()
    )

    @Test
    fun appShelfPreservesOpenRenameAndExplicitDeletion() {
        var opened: ProjectId? = null
        var renamed = ""
        var deleted: ProjectId? = null
        compose.setContent {
            TransmogrifierTheme(darkTheme = false) {
                ProjectsScreen(
                    listOf(app),
                    onOpen = { opened = it },
                    onCreate = {},
                    onRename = { _, name -> renamed = name },
                    onDelete = { deleted = it },
                    onSettings = {}
                )
            }
        }
        compose.onNodeWithText("Transmogrifier").assertIsDisplayed()
        screenshot("projects-light")
        compose.onNodeWithText("Game night").performClick()
        assertEquals(id, opened)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Rename").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(" Friday games ")
        compose.onNodeWithText("OK").performClick()
        assertEquals("Friday games", renamed)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete app").performClick()
        compose.onNodeWithText("Keep app").performClick()
        assertEquals(null, deleted)
        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Delete app").performClick()
        compose.onNodeWithText("Delete app").performClick()
        assertEquals(id, deleted)
    }

    @Test
    fun examplesFillDraftWithoutSubmittingAndFollowUpStillWorks() {
        val requests = mutableListOf<String>()
        var opened = 0
        var stopped = 0
        val state = mutableStateOf(
            WorkspaceUiState(
                project = Project(id, "Game night", ProviderId.ANTHROPIC, "test", 0, 0)
            )
        )
        compose.setContent {
            TransmogrifierTheme(darkTheme = false) {
                WorkspaceScreen(
                    state.value,
                    onBack = {},
                    onRun = { opened++ },
                    onSend = { requests += it },
                    onStop = { stopped++ },
                    onFixErrors = {},
                    onResume = {},
                    onOpenSettings = {},
                    onOpenFile = {}
                )
            }
        }
        screenshot("workspace")
        compose.onNodeWithText("Weekend away").performClick()
        assertEquals(emptyList<String>(), requests)
        val request = "A packing checklist for a weekend away. Let me add and check off items."
        compose.onNode(hasSetTextAction()).assertTextContains(request)
        compose.onNodeWithContentDescription("Make app").performClick()
        assertEquals(listOf(request), requests)
        compose.runOnIdle {
            state.value = state.value.copy(
                transcript = listOf(TranscriptEntry.Prompt(request)),
                files = listOf(FileEntry("index.html", 100))
            )
        }
        compose.onNodeWithContentDescription("Open app").assertIsEnabled().performClick()
        assertEquals(1, opened)
        compose.onNode(hasSetTextAction()).performTextReplacement("Make the buttons bigger.")
        compose.onNodeWithContentDescription("Send").performClick()
        assertEquals("Make the buttons bigger.", requests.last())
        compose.runOnIdle { state.value = state.value.copy(generating = true) }
        compose.onNodeWithContentDescription("Stop").performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun advancedSettingsRemainAvailableBehindSimpleSetup() {
        var steps = 0
        compose.setContent {
            TransmogrifierTheme(darkTheme = false) {
                SettingsScreen(
                    SettingsUiState(),
                    onBack = {},
                    onSaveKey = { _, _ -> },
                    onClearKey = {},
                    onTestKey = {},
                    onDefaultProvider = {},
                    onDefaultModel = { _, _ -> },
                    onEffort = {},
                    onMaxToolIterations = { steps = it },
                    onMaxOutputTokens = {}
                )
            }
        }
        screenshot("settings")
        compose.onNodeWithText("Steps per request").assertDoesNotExist()
        compose.onNodeWithText("Advanced settings").performScrollTo().performClick()
        compose.onNodeWithText("Thinking effort").performScrollTo().assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Steps per request"))
            .performScrollTo().performTextReplacement("24")
        assertEquals(24, steps)
    }

    @Test
    fun emptyShelfSupportsLargeTextAndCreation() {
        var created = ""
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TransmogrifierTheme(darkTheme = false) {
                    ProjectsScreen(
                        emptyList(),
                        {},
                        { created = it },
                        { _, _ -> },
                        {},
                        {}
                    )
                }
            }
        }
        screenshot("large-text")
        compose.onNodeWithContentDescription("New app").assertIsDisplayed().performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(" Weekend away ")
        compose.onNodeWithText("OK").performClick()
        assertEquals("Weekend away", created)
    }

    @Test
    fun darkShelfKeepsPrimaryActionsVisible() {
        compose.setContent {
            TransmogrifierTheme(darkTheme = true) {
                ProjectsScreen(listOf(app), {}, {}, { _, _ -> }, {}, {})
            }
        }
        screenshot("projects-dark")
        compose.onNodeWithContentDescription("New app").assertIsDisplayed()
        compose.onNodeWithText("Game night").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "branding-$name.png")
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
