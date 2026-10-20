package io.github.jambolo.transmogrifier.ui.workspace

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jambolo.transmogrifier.AppContainer
import io.github.jambolo.transmogrifier.R
import io.github.jambolo.transmogrifier.agent.GenerationEvent
import io.github.jambolo.transmogrifier.llm.ProviderId
import io.github.jambolo.transmogrifier.project.FileEntry
import io.github.jambolo.transmogrifier.project.NoticeKind
import io.github.jambolo.transmogrifier.project.Project
import io.github.jambolo.transmogrifier.project.ProjectId
import io.github.jambolo.transmogrifier.project.TranscriptEntry
import io.github.jambolo.transmogrifier.runtime.RunApp
import io.github.jambolo.transmogrifier.settings.Settings
import io.github.jambolo.transmogrifier.ui.providerLabel
import io.github.jambolo.transmogrifier.ui.theme.TransmogrifierTheme
import io.github.jambolo.transmogrifier.ui.toolLabel
import java.text.NumberFormat

@Composable
fun WorkspaceRoute(
    container: AppContainer,
    projectId: ProjectId,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val viewModel = viewModel {
        WorkspaceViewModel(
            projectId,
            createSavedStateHandle(),
            container.projectStore,
            container.generationEngine
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by container.settingsRepository.settings
        .collectAsStateWithLifecycle(Settings())
    val runner = rememberLauncherForActivityResult(RunApp) { result ->
        result?.let { viewModel.onRunFinished(it.errorLog, it.fixErrors) }
    }
    LaunchedEffect(state.projectMissing) { if (state.projectMissing) onBack() }
    WorkspaceScreen(
        state,
        onBack = onBack,
        onRun = {
            state.project?.let { runner.launch(RunApp.Input(it, settings.allowAppNetwork)) }
        },
        onSend = viewModel::send,
        onStop = viewModel::stop,
        onFixErrors = viewModel::fixErrors,
        onResume = viewModel::resume,
        onOpenSettings = onOpenSettings,
        onOpenFile = viewModel::openFile
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    state: WorkspaceUiState,
    onBack: () -> Unit,
    onRun: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onFixErrors: () -> Unit,
    onResume: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFile: (String?) -> Unit
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var draft by rememberSaveable { mutableStateOf("") }
    val firstRequest =
        state.files.isEmpty() && state.transcript.none { it is TranscriptEntry.Prompt }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.project?.name.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        state.project?.let {
                            Text(
                                stringResource(
                                    R.string.provider_model,
                                    providerLabel(it.provider),
                                    it.model
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRun,
                        enabled = state.files.isNotEmpty() && !state.generating
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_brand_play),
                            stringResource(R.string.run)
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (tab == 0) {
                PromptBar(
                    generating = state.generating,
                    canFixErrors = state.consoleErrors != null,
                    firstRequest = firstRequest,
                    draft = draft,
                    onDraftChange = { draft = it },
                    onSend = onSend,
                    onStop = onStop,
                    onFixErrors = onFixErrors
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.tab_chat)) }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.tab_files, state.files.size)) }
                )
            }
            if (tab == 0) {
                ChatPane(
                    state.transcript,
                    state.generating,
                    state.live,
                    onResume,
                    onOpenSettings,
                    { draft = it },
                    Modifier.weight(1f)
                )
            } else {
                FilesPane(state.files, state.openFile, onOpenFile, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChatPane(
    transcript: List<TranscriptEntry>,
    generating: Boolean,
    live: LiveTurn,
    onResume: () -> Unit,
    onOpenSettings: () -> Unit,
    onExample: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (transcript.isEmpty() && !generating) {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
        ) {
            Text(
                stringResource(R.string.chat_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, request) in listOf(
                    R.string.example_scorekeeper to R.string.prompt_scorekeeper,
                    R.string.example_packing to R.string.prompt_packing,
                    R.string.example_lunch to R.string.prompt_lunch
                )) {
                    val prompt = stringResource(request)
                    SuggestionChip(
                        onClick = { onExample(prompt) },
                        label = { Text(stringResource(label)) }
                    )
                }
            }
        }
        return
    }
    // Reversed so the newest entry stays anchored to the bottom while it streams.
    LazyColumn(
        modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)
    ) {
        if (generating) {
            item(key = "live") { LiveEntry(live) }
        } else {
            (transcript.lastOrNull() as? TranscriptEntry.Notice)?.let { notice ->
                item(key = "actions") { NoticeActions(notice.kind, onResume, onOpenSettings) }
            }
        }
        items(transcript.asReversed()) { TranscriptItem(it) }
    }
}

@Composable
private fun TranscriptItem(entry: TranscriptEntry) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (entry) {
        is TranscriptEntry.Prompt -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    if (entry.fixErrors) {
                        Text(
                            stringResource(R.string.fix_errors),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            entry.text,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text(entry.text)
                    }
                }
            }
        }

        is TranscriptEntry.Reply -> Text(entry.text, style = MaterialTheme.typography.bodyLarge)

        is TranscriptEntry.Tool -> Row(verticalAlignment = Alignment.CenterVertically) {
            val color = if (entry.error == null) muted else MaterialTheme.colorScheme.error
            Icon(
                if (entry.error == null) Icons.Default.Build else Icons.Default.Warning,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    toolLabel(entry.name, entry.path, running = false),
                    style = MaterialTheme.typography.labelLarge,
                    color = color
                )
                entry.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = color)
                }
            }
        }

        is TranscriptEntry.Usage -> {
            val format = NumberFormat.getIntegerInstance()
            Text(
                stringResource(
                    R.string.usage,
                    format.format(entry.inputTokens),
                    format.format(entry.outputTokens),
                    format.format(entry.cacheReadTokens)
                ),
                style = MaterialTheme.typography.labelSmall,
                color = muted
            )
        }

        is TranscriptEntry.Notice -> {
            val isError = entry.kind == NoticeKind.FAILED ||
                entry.kind == NoticeKind.AUTH_FAILED ||
                entry.kind == NoticeKind.PROVIDER_UNAVAILABLE
            Text(
                when (entry.kind) {
                    NoticeKind.STOPPED -> stringResource(R.string.notice_stopped)
                    NoticeKind.ITERATION_LIMIT -> stringResource(R.string.notice_iteration_limit)
                    NoticeKind.TRUNCATED -> stringResource(R.string.notice_truncated)
                    NoticeKind.REFUSED -> stringResource(R.string.notice_refused)
                    NoticeKind.AUTH_FAILED -> stringResource(R.string.notice_auth_failed)
                    NoticeKind.FAILED -> stringResource(
                        R.string.notice_failed,
                        entry.detail.orEmpty()
                    )

                    NoticeKind.PROVIDER_UNAVAILABLE ->
                        stringResource(R.string.notice_provider_unavailable)
                },
                style = MaterialTheme.typography.labelMedium,
                fontStyle = FontStyle.Italic,
                color = if (isError) MaterialTheme.colorScheme.error else muted
            )
        }
    }
}

@Composable
private fun NoticeActions(kind: NoticeKind, onResume: () -> Unit, onOpenSettings: () -> Unit) {
    when (kind) {
        NoticeKind.STOPPED, NoticeKind.ITERATION_LIMIT, NoticeKind.FAILED ->
            OutlinedButton(onClick = onResume) {
                Text(stringResource(R.string.continue_generation))
            }

        NoticeKind.AUTH_FAILED ->
            OutlinedButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.open_settings))
            }

        NoticeKind.TRUNCATED, NoticeKind.REFUSED, NoticeKind.PROVIDER_UNAVAILABLE -> Unit
    }
}

@Composable
private fun LiveEntry(live: LiveTurn) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (live.text.isNotEmpty()) {
            Text(live.text, style = MaterialTheme.typography.bodyLarge)
        }
        if (live.tool != null || live.text.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        live.retry != null -> pluralStringResource(
                            R.plurals.retrying,
                            live.retry.delaySeconds,
                            live.retry.delaySeconds,
                            live.retry.reason
                        )

                        live.tool != null -> toolLabel(
                            live.tool.name,
                            live.tool.path,
                            running = true
                        )

                        else -> stringResource(R.string.thinking)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PromptBar(
    generating: Boolean,
    canFixErrors: Boolean,
    firstRequest: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onFixErrors: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (canFixErrors && !generating) {
                AssistChip(
                    onClick = onFixErrors,
                    label = { Text(stringResource(R.string.fix_errors)) },
                    leadingIcon = { Icon(Icons.Default.Warning, null, Modifier.size(18.dp)) }
                )
                Text(
                    stringResource(R.string.fix_disclosure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    label = {
                        Text(
                            stringResource(
                                if (firstRequest) {
                                    R.string.first_request
                                } else {
                                    R.string.change_request
                                }
                            )
                        )
                    },
                    placeholder = { Text(stringResource(R.string.prompt_hint)) },
                    maxLines = 5,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                if (generating) {
                    FilledIconButton(onClick = onStop) {
                        Icon(
                            painterResource(R.drawable.ic_brand_close),
                            stringResource(R.string.stop)
                        )
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            onSend(draft.trim())
                            onDraftChange("")
                        },
                        enabled = draft.isNotBlank()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            stringResource(if (firstRequest) R.string.make_app else R.string.send)
                        )
                    }
                }
            }
        }
    }
}

private val previewState = WorkspaceUiState(
    project = Project(
        ProjectId("1"),
        "Tip calculator",
        ProviderId.ANTHROPIC,
        "claude-opus-5-5",
        0,
        0
    ),
    transcript = listOf(
        TranscriptEntry.Prompt("A tip calculator with a slider for the percentage"),
        TranscriptEntry.Reply("Sure, building that now."),
        TranscriptEntry.Tool("write_file", "index.html"),
        TranscriptEntry.Tool("edit_file", "js/app.js", "old_text not found in js/app.js"),
        TranscriptEntry.Usage(4_210, 1_388, 3_072),
        TranscriptEntry.Prompt(
            "Uncaught ReferenceError: total is not defined (app.js:9)",
            fixErrors = true
        )
    ),
    generating = true,
    live = LiveTurn(
        "Let me look at those errors. ",
        GenerationEvent.ToolRunning("read_file", "js/app.js")
    ),
    files = listOf(FileEntry("index.html", 512), FileEntry("js/app.js", 830))
)

@Preview(showBackground = true)
@Composable
private fun WorkspaceScreenPreview() {
    TransmogrifierTheme {
        WorkspaceScreen(previewState, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true)
@Composable
private fun WorkspaceScreenIdlePreview() {
    TransmogrifierTheme {
        WorkspaceScreen(
            previewState.copy(
                transcript = previewState.transcript + TranscriptEntry.Notice(NoticeKind.STOPPED),
                generating = false,
                live = LiveTurn(),
                consoleErrors = "error"
            ),
            {},
            {},
            {},
            {},
            {},
            {},
            {},
            {}
        )
    }
}
