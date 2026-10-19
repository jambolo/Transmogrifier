package io.github.jambolo.transmogrifier.ui.projects

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import io.github.jambolo.transmogrifier.ui.theme.Citrus
import io.github.jambolo.transmogrifier.ui.theme.Coral
import io.github.jambolo.transmogrifier.ui.theme.Ink
import io.github.jambolo.transmogrifier.ui.theme.Lilac
import io.github.jambolo.transmogrifier.ui.theme.Sky
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
    val newAppLabel = stringResource(R.string.new_app)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_brand_symbol),
                            null,
                            Modifier.size(32.dp)
                        )
                        Text(
                            stringResource(R.string.app_name),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
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
                modifier = Modifier.semantics { contentDescription = newAppLabel },
                icon = { Icon(painterResource(R.drawable.ic_brand_add), null) },
                text = { Text(newAppLabel) }
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 104.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(
                    Modifier.padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.brand_tagline),
                        style = MaterialTheme.typography.headlineLarge
                    )
                    Text(
                        stringResource(R.string.apps_lifecycle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (projects.isEmpty()) {
                item {
                    Column(
                        Modifier.padding(vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            color = Citrus,
                            contentColor = Ink,
                            shape = MaterialTheme.shapes.large
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_brand_symbol),
                                null,
                                Modifier.padding(16.dp).size(56.dp)
                            )
                        }
                        Text(
                            stringResource(R.string.empty_apps_title),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            stringResource(R.string.no_apps),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                item {
                    Text(
                        stringResource(R.string.your_apps),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
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
                    Text(stringResource(R.string.keep_app))
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
    val accents = listOf(Citrus, Sky, Lilac, Coral)
    val accent = accents[(project.id.value.hashCode() and Int.MAX_VALUE) % accents.size]
    OutlinedCard(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(color = accent, contentColor = Ink, shape = MaterialTheme.shapes.medium) {
                Icon(
                    painterResource(R.drawable.ic_brand_symbol),
                    null,
                    Modifier.padding(10.dp).size(28.dp)
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(
                        R.string.project_subtitle,
                        edited,
                        providerLabel(project.provider)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_brand_edit), null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_brand_delete), null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
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
                supportingText = { Text(stringResource(R.string.app_name_hint)) },
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
