package io.github.jambolo.transmogrifier.ui.workspace

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jambolo.transmogrifier.R
import io.github.jambolo.transmogrifier.project.FileEntry
import io.github.jambolo.transmogrifier.ui.theme.TransmogrifierTheme

private sealed interface TreeRow {
    val depth: Int

    data class Directory(val name: String, override val depth: Int) : TreeRow

    data class File(val entry: FileEntry, override val depth: Int) : TreeRow
}

/** Flattens sorted paths into a tree, emitting each directory once before its contents. */
private fun treeRows(files: List<FileEntry>): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()
    var openDirs = emptyList<String>()
    for (entry in files.sortedBy { it.path }) {
        val dirs = entry.path.split('/').dropLast(1)
        val shared = openDirs.zip(dirs).takeWhile { (a, b) -> a == b }.size
        dirs.drop(shared).forEachIndexed { i, name -> rows += TreeRow.Directory(name, shared + i) }
        rows += TreeRow.File(entry, dirs.size)
        openDirs = dirs
    }
    return rows
}

@Composable
fun FilesPane(
    files: List<FileEntry>,
    openFile: OpenFile?,
    onOpenFile: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    if (openFile != null) {
        BackHandler { onOpenFile(null) }
        FileViewer(openFile, onClose = { onOpenFile(null) }, modifier)
        return
    }
    if (files.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.no_files),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize()) {
        items(treeRows(files)) { row ->
            val indent = Modifier.padding(start = (16 * row.depth).dp)
            when (row) {
                is TreeRow.Directory -> ListItem(
                    headlineContent = {
                        Text("${row.name}/", style = MaterialTheme.typography.titleSmall)
                    },
                    modifier = indent
                )

                is TreeRow.File -> ListItem(
                    headlineContent = { Text(row.entry.path.substringAfterLast('/')) },
                    trailingContent = {
                        Text(Formatter.formatShortFileSize(context, row.entry.sizeBytes))
                    },
                    modifier = Modifier.clickable { onOpenFile(row.entry.path) }.then(indent)
                )
            }
        }
    }
}

@Composable
fun FileViewer(file: OpenFile, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = SyntaxColors(
        comment = scheme.outline,
        string = scheme.tertiary,
        keyword = scheme.primary,
        tag = scheme.secondary,
        number = scheme.error
    )
    val text = remember(file, colors) { file.content?.let { highlight(it, file.path, colors) } }
    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, stringResource(R.string.close))
            }
            Text(file.path, style = MaterialTheme.typography.titleSmall)
        }
        HorizontalDivider()
        if (text == null) {
            Text(stringResource(R.string.file_unreadable), Modifier.padding(16.dp))
        } else {
            SelectionContainer {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(16.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun FilesPanePreview() {
    TransmogrifierTheme {
        FilesPane(
            files = listOf(
                FileEntry("index.html", 512),
                FileEntry("css/style.css", 204),
                FileEntry("js/app.js", 1_830),
                FileEntry("js/lib/util.js", 96)
            ),
            openFile = null,
            onOpenFile = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun FileViewerPreview() {
    TransmogrifierTheme {
        FileViewer(
            OpenFile(
                "js/app.js",
                """
                // Count taps
                const button = document.getElementById("count");
                let count = Number(localStorage.getItem("count") ?? 0);
                button.addEventListener("click", () => { count += 1; });
                """.trimIndent()
            ),
            onClose = {}
        )
    }
}
