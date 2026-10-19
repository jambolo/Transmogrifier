package io.github.jambolo.transmogrifier.ui

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
import io.github.jambolo.transmogrifier.AppContainer
import io.github.jambolo.transmogrifier.project.ProjectId
import io.github.jambolo.transmogrifier.ui.projects.ProjectsRoute
import io.github.jambolo.transmogrifier.ui.settings.SettingsRoute
import io.github.jambolo.transmogrifier.ui.workspace.WorkspaceRoute
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

sealed interface Destination {
    @Serializable
    data object Projects : Destination

    @Serializable
    data class Workspace(val projectId: String) : Destination

    @Serializable
    data object Settings : Destination
}

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    var firstRunChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!firstRunChecked) {
            firstRunChecked = true
            val hints = container.settingsRepository.settings.first().apiKeyHints
            if (hints.keys.none { it.enabled }) {
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
                onBack = { navController.navigateUp() },
                onOpenSettings = { navController.navigate(Destination.Settings) }
            )
        }
        composable<Destination.Settings> {
            SettingsRoute(container, onBack = { navController.navigateUp() })
        }
    }
}
