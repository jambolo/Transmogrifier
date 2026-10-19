package com.example.transmogrifier.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.transmogrifier.AppContainer
import com.example.transmogrifier.project.ProjectId
import com.example.transmogrifier.ui.projects.ProjectsRoute
import com.example.transmogrifier.ui.runner.RunnerRoute
import com.example.transmogrifier.ui.settings.SettingsRoute
import com.example.transmogrifier.ui.workspace.WorkspaceRoute
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

sealed interface Destination {
    @Serializable
    data object Projects : Destination

    @Serializable
    data class Workspace(val projectId: String) : Destination

    @Serializable
    data class Runner(val projectId: String) : Destination

    @Serializable
    data object Settings : Destination
}

/** Saved-state keys the runner sets on the workspace's back stack entry. */
object RunnerResults {
    /** Error log from the last run, or null when it had no errors. */
    const val CONSOLE_ERRORS = "consoleErrors"

    /** Error log the user chose to send to the LLM; the workspace starts a fix and clears it. */
    const val FIX_ERRORS = "fixErrors"
}

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    var firstRunChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!firstRunChecked) {
            firstRunChecked = true
            if (container.settingsRepository.settings.first().apiKeyHints.isEmpty()) {
                navController.navigate(Destination.Settings)
            }
        }
    }
    NavHost(navController, startDestination = Destination.Projects) {
        composable<Destination.Projects> {
            ProjectsRoute(
                container,
                onOpen = { navController.navigate(Destination.Workspace(it.value)) },
                onSettings = { navController.navigate(Destination.Settings) }
            )
        }
        composable<Destination.Workspace> { entry ->
            val projectId = ProjectId(entry.toRoute<Destination.Workspace>().projectId)
            WorkspaceRoute(
                container,
                projectId,
                entry.savedStateHandle,
                onBack = { navController.navigateUp() },
                onRun = { navController.navigate(Destination.Runner(projectId.value)) },
                onOpenSettings = { navController.navigate(Destination.Settings) }
            )
        }
        composable<Destination.Runner> { entry ->
            val projectId = ProjectId(entry.toRoute<Destination.Runner>().projectId)
            RunnerRoute(
                container,
                projectId,
                onBack = { navController.navigateUp() },
                onErrorsChanged = { log ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        RunnerResults.CONSOLE_ERRORS,
                        log
                    )
                },
                onSendErrors = { log ->
                    navController.previousBackStackEntry?.savedStateHandle?.set(
                        RunnerResults.FIX_ERRORS,
                        log
                    )
                    navController.navigateUp()
                }
            )
        }
        composable<Destination.Settings> {
            SettingsRoute(container, onBack = { navController.navigateUp() })
        }
    }
}
