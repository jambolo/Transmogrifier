package io.github.jambolo.transmogrifier.runtime

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import io.github.jambolo.transmogrifier.project.Project

/** Runs a project in [RunnerActivity]; the result is null when the runner ended without reporting. */
object RunApp : ActivityResultContract<RunApp.Input, RunApp.Result?>() {
    /** [allowNetwork] is passed in because the runner process never reads settings. */
    data class Input(val project: Project, val allowNetwork: Boolean)

    /** [errorLog] is null when the run had no errors; [fixErrors] asks for a fix generation. */
    data class Result(val errorLog: String?, val fixErrors: Boolean)

    internal const val EXTRA_PROJECT_ID = "projectId"
    internal const val EXTRA_PROJECT_NAME = "projectName"
    internal const val EXTRA_NETWORK = "network"
    internal const val EXTRA_ERRORS = "errors"
    internal const val EXTRA_FIX = "fix"

    override fun createIntent(context: Context, input: Input) =
        Intent(context, RunnerActivity::class.java)
            .putExtra(EXTRA_PROJECT_ID, input.project.id.value)
            .putExtra(EXTRA_PROJECT_NAME, input.project.name)
            .putExtra(EXTRA_NETWORK, input.allowNetwork)

    override fun parseResult(resultCode: Int, intent: Intent?): Result? =
        intent?.takeIf { resultCode == Activity.RESULT_OK }?.let {
            Result(it.getStringExtra(EXTRA_ERRORS), it.getBooleanExtra(EXTRA_FIX, false))
        }

    internal fun result(errorLog: String?, fixErrors: Boolean) =
        Intent().putExtra(EXTRA_ERRORS, errorLog).putExtra(EXTRA_FIX, fixErrors)
}
