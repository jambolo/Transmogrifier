package io.github.jambolo.transmogrifier.project

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WorkspaceLimits(
    val maxPathLength: Int = 200,
    val maxDepth: Int = 6,
    val maxFiles: Int = 100,
    val maxFileBytes: Long = 512L * 1024,
    val maxTotalBytes: Long = 4L * 1024 * 1024,
    val allowedExtensions: Set<String> =
        setOf("html", "css", "js", "mjs", "json", "svg", "txt", "md")
)

data class FileEntry(val path: String, val sizeBytes: Long)

/** Rejections are values, not exceptions, so they can go back to the model as error tool results. */
sealed interface WorkspaceResult<out T> {
    data class Ok<T>(val value: T) : WorkspaceResult<T>

    data class Error(val message: String) : WorkspaceResult<Nothing>
}

/** Sandboxed text-file access to one generated site. Paths are relative and `/`-separated. */
class Workspace(private val root: File, private val limits: WorkspaceLimits = WorkspaceLimits()) {
    suspend fun listFiles(): List<FileEntry> = withContext(Dispatchers.IO) { entries() }

    suspend fun read(path: String): WorkspaceResult<String> = withFile(path) { file ->
        if (file.isFile) {
            WorkspaceResult.Ok(
                file.readText()
            )
        } else {
            WorkspaceResult.Error("File not found: $path")
        }
    }

    suspend fun write(path: String, content: String): WorkspaceResult<Unit> =
        withFile(path) { file ->
            val bytes = content.toByteArray()
            val existing = entries()
            val oldSize = if (file.isFile) file.length() else 0L
            when {
                file.isDirectory -> {
                    WorkspaceResult.Error("Path is a directory: $path")
                }

                bytes.size > limits.maxFileBytes -> {
                    WorkspaceResult.Error("File exceeds ${limits.maxFileBytes} bytes: $path")
                }

                !file.isFile && existing.size >= limits.maxFiles -> {
                    WorkspaceResult.Error("Too many files (limit ${limits.maxFiles})")
                }

                existing.sumOf { it.sizeBytes } - oldSize + bytes.size > limits.maxTotalBytes -> {
                    WorkspaceResult.Error("Site exceeds ${limits.maxTotalBytes} bytes in total")
                }

                else -> {
                    file.parentFile!!.mkdirs()
                    file.writeBytes(bytes)
                    WorkspaceResult.Ok(Unit)
                }
            }
        }

    /** Replaces exactly one occurrence of [oldText]. */
    suspend fun edit(path: String, oldText: String, newText: String): WorkspaceResult<Unit> {
        if (oldText.isEmpty()) return WorkspaceResult.Error("old_text must not be empty")
        val content =
            when (val result = read(path)) {
                is WorkspaceResult.Error -> return result
                is WorkspaceResult.Ok -> result.value
            }
        val first = content.indexOf(oldText)
        return when {
            first < 0 -> WorkspaceResult.Error("old_text not found in $path")
            content.indexOf(
                oldText,
                first + 1
            ) >= 0 -> WorkspaceResult.Error("old_text matches more than once in $path")
            else -> write(path, content.replaceRange(first, first + oldText.length, newText))
        }
    }

    suspend fun delete(path: String): WorkspaceResult<Unit> = withFile(path) { file ->
        if (!file.isFile) return@withFile WorkspaceResult.Error("File not found: $path")
        file.delete()
        var dir = file.parentFile
        while (dir != null && dir != canonicalRoot && dir.list()?.isEmpty() == true) {
            dir.delete()
            dir = dir.parentFile
        }
        WorkspaceResult.Ok(Unit)
    }

    private val canonicalRoot: File by lazy { root.canonicalFile }

    private fun entries(): List<FileEntry> = canonicalRoot
        .walkTopDown()
        .filter { it.isFile }
        .map { FileEntry(it.relativeTo(canonicalRoot).invariantSeparatorsPath, it.length()) }
        .sortedBy { it.path }
        .toList()

    private suspend fun <T> withFile(
        path: String,
        block: (File) -> WorkspaceResult<T>
    ): WorkspaceResult<T> = withContext(Dispatchers.IO) {
        when (val resolved = resolve(path)) {
            is WorkspaceResult.Error -> resolved
            is WorkspaceResult.Ok -> block(resolved.value)
        }
    }

    internal fun resolve(path: String): WorkspaceResult<File> {
        val segments = path.split('/')
        val extension = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        val reason =
            when {
                path.isEmpty() -> "Path is empty"

                path.length > limits.maxPathLength ->
                    "Path longer than ${limits.maxPathLength} characters"

                path.startsWith('/') -> "Absolute paths are not allowed"

                path.any { it == '\\' || it == ':' || it.isISOControl() } ->
                    "Path contains a disallowed character"

                segments.any { it.isEmpty() || it.startsWith('.') } ->
                    "Path segments must not be empty or start with '.'"

                segments.size > limits.maxDepth -> "Path deeper than ${limits.maxDepth} levels"

                extension !in limits.allowedExtensions ->
                    "Extension not allowed; use one of ${limits.allowedExtensions.sorted()}"

                else -> null
            }
        if (reason != null) return WorkspaceResult.Error("$reason: $path")
        val file = File(canonicalRoot, path).canonicalFile
        // Defense in depth against symlinks inside the site directory.
        return if (file.toPath().startsWith(canonicalRoot.toPath())) {
            WorkspaceResult.Ok(file)
        } else {
            WorkspaceResult.Error("Path escapes the site directory: $path")
        }
    }
}
