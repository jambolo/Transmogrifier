package io.github.jambolo.transmogrifier.project

import io.github.jambolo.transmogrifier.llm.ProviderId
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** An archive that can't be imported; [message] says why. */
class ArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Default provider and model for an archive that holds only a site. */
data class ImportDefaults(val provider: ProviderId, val model: String)

/**
 * A zip read into memory and checked, ready to [ProjectArchive.import]. [conversation] is null for a site-only
 * archive or a backup made before the project had a conversation.
 */
class ArchiveContents internal constructor(
    val name: String,
    internal val project: Project?,
    internal val transcript: List<TranscriptEntry>,
    internal val conversation: Conversation?,
    internal val site: Map<String, String>
) {
    val hasConversation: Boolean get() = conversation != null
}

/**
 * Exports and imports projects as zips. A site export holds only the generated app, ready to host anywhere. A backup
 * adds [MANIFEST], `project.json`, `transcript.jsonl`, and `conversation.json`, with the site under `site/`.
 *
 * Imported archives are untrusted: every site file goes through [Workspace], so its path and size limits apply, and
 * a failed import leaves no project behind. The conversation history is copied unchanged.
 */
class ProjectArchive(private val store: ProjectStore) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun exportSite(id: ProjectId, output: OutputStream) {
        val files = siteFiles(id)
        withContext(Dispatchers.IO) {
            ZipOutputStream(output).use { zip ->
                files.forEach { (path, content) -> zip.put(path, content) }
            }
        }
    }

    suspend fun exportBackup(id: ProjectId, output: OutputStream) {
        val project = requireNotNull(store.project(id).first()) { "Unknown project $id" }
        val transcript = store.transcript(id).first()
        val conversation = store.conversation(id)
        val files = siteFiles(id)
        withContext(Dispatchers.IO) {
            ZipOutputStream(output).use { zip ->
                zip.put(
                    MANIFEST,
                    json.encodeToString(Manifest.serializer(), Manifest(FORMAT, VERSION))
                )
                zip.put(PROJECT, json.encodeToString(Project.serializer(), project))
                zip.put(
                    TRANSCRIPT,
                    transcript.joinToString("") {
                        json.encodeToString(TranscriptEntry.serializer(), it) + "\n"
                    }
                )
                conversation?.let {
                    zip.put(CONVERSATION, json.encodeToString(Conversation.serializer(), it))
                }
                files.forEach { (path, content) -> zip.put("$SITE$path", content) }
            }
        }
    }

    /**
     * Reads and checks a backup or a site-only zip. A site-only zip is named [fallbackName]; if all its files are in
     * one folder, that folder becomes the site.
     *
     * @throws ArchiveException if the archive is unreadable, too large, or not a valid backup.
     */
    suspend fun read(input: InputStream, fallbackName: String): ArchiveContents =
        withContext(Dispatchers.IO) {
            val entries = try {
                unzip(input)
            } catch (e: ZipException) {
                throw ArchiveException("This isn't a readable zip file", e)
            }
            if (MANIFEST in entries) backup(entries) else site(entries, fallbackName)
        }

    /**
     * Creates a new project from [contents]. Without [withConversation], the model starts a new conversation that
     * sees only the current files, and the transcript says so.
     *
     * @throws ArchiveException if a site file breaks the workspace rules; no project is left behind.
     */
    suspend fun import(
        contents: ArchiveContents,
        defaults: ImportDefaults,
        withConversation: Boolean
    ): Project {
        val source = contents.project
        val project = store.create(
            contents.name,
            source?.provider ?: defaults.provider,
            source?.model ?: defaults.model
        )
        try {
            val workspace = store.workspace(project.id)
            contents.site.forEach { (path, content) ->
                val result = workspace.write(path, content)
                if (result is WorkspaceResult.Error) throw ArchiveException(result.message)
            }
            contents.transcript.forEach { store.appendTranscript(project.id, it) }
            val conversation = contents.conversation
            if (withConversation && conversation != null) {
                store.saveConversation(project.id, conversation)
            } else if (contents.transcript.isNotEmpty()) {
                store.appendTranscript(
                    project.id,
                    TranscriptEntry.Notice(NoticeKind.IMPORTED_WITHOUT_CONVERSATION)
                )
            }
        } catch (e: Exception) {
            withContext(NonCancellable) { store.delete(project.id) }
            throw e
        }
        return project
    }

    private suspend fun siteFiles(id: ProjectId): List<Pair<String, String>> {
        val workspace = store.workspace(id)
        return workspace.listFiles().mapNotNull { entry ->
            (workspace.read(entry.path) as? WorkspaceResult.Ok)?.let { entry.path to it.value }
        }
    }

    private fun backup(entries: Map<String, ByteArray>): ArchiveContents {
        val manifest = decode("manifest", entries.getValue(MANIFEST)) {
            json.decodeFromString(Manifest.serializer(), it)
        }
        if (manifest.format != FORMAT) throw ArchiveException("This isn't a Transmogrifier backup")
        if (manifest.version > VERSION) {
            throw ArchiveException("This backup is from a newer version of Transmogrifier")
        }
        val projectBytes = entries[PROJECT] ?: throw ArchiveException("The backup has no project")
        val project = decode("project", projectBytes) {
            json.decodeFromString(Project.serializer(), it)
        }
        // Lines from a newer version that this one doesn't know are dropped, as FileProjectStore does.
        val transcript = entries[TRANSCRIPT]?.decodeToString().orEmpty().lines()
            .filter { it.isNotBlank() }
            .mapNotNull {
                runCatching { json.decodeFromString(TranscriptEntry.serializer(), it) }.getOrNull()
            }
        val conversation = entries[CONVERSATION]?.let { bytes ->
            decode("conversation", bytes) { json.decodeFromString(Conversation.serializer(), it) }
        }
        if (conversation != null && conversation.history.provider != project.provider) {
            throw ArchiveException("The backup's conversation doesn't match its AI service")
        }
        val site = entries.filterKeys { it.startsWith(SITE) }
            .mapKeys { it.key.removePrefix(SITE) }
            .let(::siteText)
        return ArchiveContents(project.name, project, transcript, conversation, site)
    }

    private fun site(entries: Map<String, ByteArray>, name: String): ArchiveContents {
        val folder = entries.keys.map { it.substringBefore('/', "") }.distinct().singleOrNull()
        val stripped = if (INDEX !in entries && !folder.isNullOrEmpty()) {
            entries.mapKeys { it.key.removePrefix("$folder/") }
        } else {
            entries
        }
        val site = siteText(stripped)
        if (INDEX !in site) throw ArchiveException("The zip has no $INDEX")
        return ArchiveContents(name, null, emptyList(), null, site)
    }

    private fun siteText(entries: Map<String, ByteArray>) =
        entries.mapValues { it.value.decodeToString() }

    private fun <T> decode(what: String, bytes: ByteArray, block: (String) -> T): T = try {
        block(bytes.decodeToString())
    } catch (e: SerializationException) {
        throw ArchiveException("The backup's $what is damaged or from a newer version", e)
    } catch (e: IllegalArgumentException) {
        throw ArchiveException("The backup's $what is damaged or from a newer version", e)
    }

    /** Reads every file, skipping folders and hidden files, within [MAX_ENTRIES] and [MAX_TOTAL_BYTES]. */
    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory || isJunk(entry.name)) continue
            if (entries.size >= MAX_ENTRIES) throw ArchiveException("The zip has too many files")
            if (entry.name in entries) throw ArchiveException("The zip repeats ${entry.name}")
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = zip.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_TOTAL_BYTES) throw ArchiveException("The zip is too large")
                buffer.write(chunk, 0, read)
            }
            entries[entry.name] = buffer.toByteArray()
        }
        if (entries.isEmpty()) throw ArchiveException("The zip is empty or isn't a zip file")
        return entries
    }

    /**
     * Files that operating systems add to zips, which no site needs. `.` and `..` aren't junk: [Workspace] rejects them
     * along with the import.
     */
    private fun isJunk(name: String) = name.split('/').any {
        it == "__MACOSX" || it == "Thumbs.db" || (it.startsWith(".") && it != "." && it != "..")
    }

    private fun ZipOutputStream.put(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray())
        closeEntry()
    }

    @Serializable
    private data class Manifest(val format: String, val version: Int)

    companion object {
        const val MANIFEST = "manifest.json"
        private const val FORMAT = "transmogrifier-project"
        private const val VERSION = 1
        private const val PROJECT = "project.json"
        private const val TRANSCRIPT = "transcript.jsonl"
        private const val CONVERSATION = "conversation.json"
        private const val SITE = "site/"
        private const val INDEX = "index.html"
        private const val MAX_ENTRIES = 1_000
        private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
        private const val BUFFER_BYTES = 8 * 1024
    }
}
