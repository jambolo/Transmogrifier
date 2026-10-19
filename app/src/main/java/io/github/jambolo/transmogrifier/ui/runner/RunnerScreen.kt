package io.github.jambolo.transmogrifier.ui.runner

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.jambolo.transmogrifier.R
import io.github.jambolo.transmogrifier.runtime.ConsoleMessage
import io.github.jambolo.transmogrifier.ui.theme.TransmogrifierTheme

/** [browser] is the page itself, supplied by the runner activity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunnerScreen(
    projectName: String,
    console: List<ConsoleMessage>,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onSendErrors: () -> Unit,
    browser: @Composable () -> Unit
) {
    var consoleOpen by rememberSaveable { mutableStateOf(false) }
    val errorCount = console.count { it.level == ConsoleMessage.Level.ERROR }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(projectName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = onReload) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.reload))
                    }
                    IconButton(onClick = { consoleOpen = !consoleOpen }) {
                        BadgedBox(badge = { if (errorCount > 0) Badge { Text("$errorCount") } }) {
                            Icon(Icons.Default.Info, stringResource(R.string.console))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).imePadding()) {
            // The open console panel pads itself for the navigation bar.
            val insets = if (consoleOpen) Modifier else Modifier.navigationBarsPadding()
            Box(Modifier.weight(1f).fillMaxWidth().then(insets)) { browser() }
            if (consoleOpen) {
                ConsolePanel(console, errorCount > 0, onSendErrors, Modifier.weight(0.6f))
            }
        }
    }
}

@Composable
private fun ConsolePanel(
    console: List<ConsoleMessage>,
    hasErrors: Boolean,
    onSendErrors: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(tonalElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.console), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSendErrors, enabled = hasErrors) {
                    Text(stringResource(R.string.send_errors))
                }
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                items(console) { message ->
                    val location = message.source.takeIf { it.isNotEmpty() }
                        ?.let { "  $it:${message.line}" }
                        .orEmpty()
                    Text(
                        message.message + location,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (message.level) {
                            ConsoleMessage.Level.LOG -> MaterialTheme.colorScheme.onSurface
                            ConsoleMessage.Level.WARNING -> MaterialTheme.colorScheme.tertiary
                            ConsoleMessage.Level.ERROR -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RunnerScreenPreview() {
    TransmogrifierTheme {
        RunnerScreen(
            "Tip calculator",
            listOf(
                ConsoleMessage(ConsoleMessage.Level.LOG, "App started", "js/app.js", 1),
                ConsoleMessage(ConsoleMessage.Level.ERROR, "Uncaught TypeError", "js/app.js", 9)
            ),
            onBack = {},
            onReload = {},
            onSendErrors = {}
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Page") }
        }
    }
}
