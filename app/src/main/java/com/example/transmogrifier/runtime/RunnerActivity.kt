package com.example.transmogrifier.runtime

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.transmogrifier.AppContainer
import com.example.transmogrifier.R
import com.example.transmogrifier.project.FileProjectStore
import com.example.transmogrifier.project.ProjectId
import com.example.transmogrifier.ui.runner.RunnerScreen
import com.example.transmogrifier.ui.theme.TransmogrifierTheme
import kotlinx.coroutines.launch

/**
 * Runs a project's generated site in the `:runner` process, so a misbehaving page can't take down the editor or
 * reach the main process's memory, where the API keys are. Started through [RunApp], which returns the errors.
 */
class RunnerActivity : ComponentActivity() {
    private var browser: SiteBrowser? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectId = intent.getStringExtra(RunApp.EXTRA_PROJECT_ID)
            ?.takeIf { PROJECT_ID.matches(it) }
            ?.let(::ProjectId)
        if (projectId == null) {
            finish()
            return
        }
        val projectName = intent.getStringExtra(RunApp.EXTRA_PROJECT_NAME).orEmpty()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Keeps this process's WebView data apart from any WebView in the main process. It throws once a
            // WebView exists, as after a restart in the same process.
            runCatching { WebView.setDataDirectorySuffix("runner") }
        }
        enableEdgeToEdge()

        val siteDir = FileProjectStore.siteDir(AppContainer.projectsRoot(this), projectId)
        val browser = SiteBrowser(this, projectId, siteDir).also { browser = it }
        browser.load()
        lifecycleScope.launch {
            browser.console.collect { setResult(RESULT_OK, RunApp.result(it.errorLog(), false)) }
        }
        onBackPressedDispatcher.addCallback(this) {
            if (!browser.crashed.value && browser.webView.canGoBack()) {
                browser.webView.goBack()
            } else {
                finish()
            }
        }
        setContent {
            TransmogrifierTheme {
                RunnerContent(
                    browser,
                    projectName,
                    onClose = ::finish,
                    onRestart = ::recreate,
                    onSendErrors = { log ->
                        setResult(RESULT_OK, RunApp.result(log, true))
                        finish()
                    },
                    onOpenLink = ::openExternally
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        browser?.takeUnless { it.crashed.value }?.webView?.run {
            onResume()
            resumeTimers()
        }
    }

    override fun onPause() {
        browser?.takeUnless { it.crashed.value }?.webView?.run {
            onPause()
            // Timers are per process, and this process only runs the page.
            pauseTimers()
        }
        super.onPause()
    }

    override fun onDestroy() {
        browser?.destroy()
        super.onDestroy()
    }

    private fun openExternally(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
        }
    }

    private companion object {
        val PROJECT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

@Composable
private fun RunnerContent(
    browser: SiteBrowser,
    projectName: String,
    onClose: () -> Unit,
    onRestart: () -> Unit,
    onSendErrors: (String) -> Unit,
    onOpenLink: (Uri) -> Unit
) {
    val console by browser.console.collectAsStateWithLifecycle()
    val dialog by browser.dialog.collectAsStateWithLifecycle()
    val crashed by browser.crashed.collectAsStateWithLifecycle()
    RunnerScreen(
        projectName,
        console,
        onBack = onClose,
        onReload = { if (crashed) onRestart() else browser.reload() },
        onSendErrors = { console.errorLog()?.let(onSendErrors) }
    ) {
        if (crashed) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.page_crashed),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            AndroidView(factory = { browser.webView }, modifier = Modifier.fillMaxSize())
        }
    }
    dialog?.let {
        PageDialogHost(it, projectName, onDone = browser::dismissDialog, onOpenLink = onOpenLink)
    }
}
