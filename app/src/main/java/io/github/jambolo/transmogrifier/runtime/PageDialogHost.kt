package io.github.jambolo.transmogrifier.runtime

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.jambolo.transmogrifier.R

/** Shows the page's [dialog] and answers it; [onDone] runs once it is answered either way. */
@Composable
fun PageDialogHost(
    dialog: PageDialog,
    projectName: String,
    onDone: () -> Unit,
    onOpenLink: (Uri) -> Unit
) {
    var input by remember(dialog) {
        mutableStateOf((dialog as? PageDialog.Prompt)?.defaultValue.orEmpty())
    }
    val cancel = {
        dialog.cancel()
        onDone()
    }
    val confirm = {
        when (dialog) {
            is PageDialog.Alert -> dialog.result.confirm()
            is PageDialog.Confirm -> dialog.result.confirm()
            is PageDialog.Prompt -> dialog.result.confirm(input)
            is PageDialog.ExternalLink -> onOpenLink(dialog.uri)
        }
        onDone()
    }
    val title = if (dialog is PageDialog.ExternalLink) {
        stringResource(R.string.open_link_title)
    } else {
        stringResource(R.string.page_says, projectName)
    }
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    when (dialog) {
                        is PageDialog.Alert -> dialog.message
                        is PageDialog.Confirm -> dialog.message
                        is PageDialog.Prompt -> dialog.message
                        is PageDialog.ExternalLink -> dialog.uri.toString()
                    }
                )
                if (dialog is PageDialog.Prompt) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = confirm) {
                Text(
                    stringResource(
                        if (dialog is PageDialog.ExternalLink) R.string.open else R.string.ok
                    )
                )
            }
        },
        dismissButton = if (dialog is PageDialog.Alert) {
            null
        } else {
            { TextButton(onClick = cancel) { Text(stringResource(R.string.cancel)) } }
        }
    )
}
