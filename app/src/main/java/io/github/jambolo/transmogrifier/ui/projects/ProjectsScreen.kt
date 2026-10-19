package io.github.jambolo.transmogrifier.ui.projects

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jambolo.transmogrifier.AppContainer
import io.github.jambolo.transmogrifier.R
import io.github.jambolo.transmogrifier.llm.ProviderId
import io.github.jambolo.transmogrifier.project.ProjectId
import io.github.jambolo.transmogrifier.project.ProjectSummary
import io.github.jambolo.transmogrifier.ui.providerLabel
import io.github.jambolo.transmogrifier.ui.theme.TransmogrifierTheme

@Composable
fun ProjectsRoute(container: AppContainer, onOpen: (ProjectId) -> Unit, onSettings: () -> Unit) {
    val viewModel = viewModel {
        ProjectsViewModel(container.projectStore, container.settingsRepository)
    }
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    ProjectsScreen(
        projects,
        onOpen = onOpen,
        onCreate = { name -> viewModel.create(name, onOpen) },
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onSettings = onSettings
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    projects: List<ProjectSummary>,
    onOpen: (ProjectId) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (ProjectId, String) -> Unit,
    onDelete: (ProjectId) -> Unit,
    onSettings: () -> Unit
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var renamingId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings))
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(stringResource(R.string.new_app)) }
            )
        }
    ) { padding ->
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.no_apps),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 88.dp
                )
            ) {
                items(projects, key = { it.id.value }) { project ->
                    ProjectRow(
                        project,
                        onOpen = { onOpen(project.id) },
                        onRename = { renamingId = project.id.value },
                        onDelete = { deletingId = project.id.value }
                    )
                }
            }
        }
    }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.new_app),
            initial = "",
            onConfirm = {
                creating = false
                onCreate(it)
            },
            onDismiss = { creating = false }
        )
    }
    projects.find { it.id.value == renamingId }?.let { project ->
        NameDialog(
            title = stringResource(R.string.rename),
            initial = project.name,
            onConfirm = {
                renamingId = null
                onRename(project.id, it)
            },
            onDismiss = { renamingId = null }
        )
    }
    projects.find { it.id.value == deletingId }?.let { project ->
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text(stringResource(R.string.delete_app_title, project.name)) },
            text = { Text(stringResource(R.string.delete_app_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deletingId = null
                    onDelete(project.id)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun ProjectRow(
    project: ProjectSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val edited = if (now - project.updatedAtMillis < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(
            project.updatedAtMillis,
            now,
            DateUtils.MINUTE_IN_MILLIS
        )
    }
    ListItem(
        headlineContent = { Text(project.name) },
        supportingContent = {
            Text(stringResource(R.string.project_subtitle, edited, providerLabel(project.provider)))
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        },
        modifier = Modifier.clickable(onClick = onOpen)
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Preview(showBackground = true)
@Composable
private fun ProjectsScreenPreview() {
    val now = System.currentTimeMillis()
    TransmogrifierTheme {
        ProjectsScreen(
            projects = listOf(
                ProjectSummary(
                    ProjectId("1"),
                    "Tip calculator",
                    ProviderId.ANTHROPIC,
                    now - 120_000
                ),
                ProjectSummary(ProjectId("2"), "Habit tracker", ProviderId.OPENAI, now - 86_400_000)
            ),
            onOpen = {},
            onCreate = {},
            onRename = { _, _ -> },
            onDelete = {},
            onSettings = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProjectsScreenEmptyPreview() {
    TransmogrifierTheme {
        ProjectsScreen(emptyList(), {}, {}, { _, _ -> }, {}, {})
    }
}
