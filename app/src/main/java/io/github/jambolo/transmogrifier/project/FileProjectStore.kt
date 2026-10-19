package io.github.jambolo.transmogrifier.project

import io.github.jambolo.transmogrifier.llm.ProviderId
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Projects on disk: `<root>/<id>/` holds `project.json`, `transcript.jsonl` (one entry per line, appended),
 * `conversation.json`, and the generated app in `site/`. Metadata and transcripts are cached in memory and loaded
 * lazily.
 */
class FileProjectStore(
    private val root: File,
    private val limits: WorkspaceLimits = WorkspaceLimits(),
    private val clock: () -> Long = System::currentTimeMillis
) : ProjectStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val projects = MutableStateFlow<Map<ProjectId, Project>?>(null)
    private val transcripts = MutableStateFlow<Map<ProjectId, List<TranscriptEntry>>>(emptyMap())

    override fun projects(): Flow<List<ProjectSummary>> = loadedProjects().map { projects ->
        projects.values.sortedByDescending { it.updatedAtMillis }.map { it.summary() }
    }

    override fun project(id: ProjectId): Flow<Project?> =
        loadedProjects().map { it[id] }.distinctUntilChanged()

    override suspend fun create(name: String, provider: ProviderId, model: String): Project {
        val now = clock()
        val project =
            Project(ProjectId(UUID.randomUUID().toString()), name, provider, model, now, now)
        io {
            dir(project.id).mkdirs()
            writeProject(project)
        }
        return project
    }

    override suspend fun rename(id: ProjectId, name: String) = io {
        touch(id) { it.copy(name = name) }
    }

    override suspend fun delete(id: ProjectId) = io {
        dir(id).deleteRecursively()
        projects.update { it?.minus(id) }
        transcripts.update { it - id }
    }

    override fun transcript(id: ProjectId): Flow<List<TranscriptEntry>> = transcripts
        .onStart { io { loadTranscript(id) } }
        .map { it[id].orEmpty() }
        .distinctUntilChanged()

    override suspend fun appendTranscript(id: ProjectId, entry: TranscriptEntry) = io {
        if (!dir(id).isDirectory) return@io
        val existing = loadTranscript(id)
        File(dir(id), TRANSCRIPT).appendText(
            json.encodeToString(TranscriptEntry.serializer(), entry) + "\n"
        )
        transcripts.update { it + (id to existing + entry) }
        touch(id) { it }
    }

    override suspend fun conversation(id: ProjectId): Conversation? = io { readConversation(id) }

    override suspend fun saveConversation(id: ProjectId, conversation: Conversation) = io {
        require(dir(id).isDirectory) { "Unknown project $id" }
        readConversation(id)?.let { old ->
            val oldItems = old.history.items
            val extends = conversation.history.items.take(oldItems.size) == oldItems
            val sameProvider = old.history.provider == conversation.history.provider
            require(extends || !sameProvider) { "Conversation history is append-only" }
        }
        writeAtomically(
            File(dir(id), CONVERSATION),
            json.encodeToString(Conversation.serializer(), conversation)
        )
    }

    override suspend fun workspace(id: ProjectId): Workspace {
        require(io { dir(id).isDirectory }) { "Unknown project $id" }
        return Workspace(siteDir(root, id), limits)
    }

    private fun loadedProjects(): Flow<Map<ProjectId, Project>> = projects
        .onStart { io { } }
        .map { it ?: emptyMap() }

    /** Runs [block] on the IO dispatcher under the store lock, after loading project metadata once. */
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (projects.value == null) projects.value = readAllProjects()
            block()
        }
    }

    private fun dir(id: ProjectId) = File(root, id.value)

    private fun readAllProjects(): Map<ProjectId, Project> = root.listFiles().orEmpty()
        .mapNotNull { dir ->
            runCatching {
                json.decodeFromString(Project.serializer(), File(dir, PROJECT).readText())
            }.getOrNull()
        }
        .associateBy { it.id }

    private fun loadTranscript(id: ProjectId): List<TranscriptEntry> {
        transcripts.value[id]?.let { return it }
        val file = File(dir(id), TRANSCRIPT)
        val entries = if (file.isFile) {
            file.readLines().filter { it.isNotBlank() }.mapNotNull {
                runCatching { json.decodeFromString(TranscriptEntry.serializer(), it) }.getOrNull()
            }
        } else {
            emptyList()
        }
        transcripts.update { it + (id to entries) }
        return entries
    }

    private fun readConversation(id: ProjectId): Conversation? {
        val file = File(dir(id), CONVERSATION)
        return if (file.isFile) {
            json.decodeFromString(
                Conversation.serializer(),
                file.readText()
            )
        } else {
            null
        }
    }

    private fun touch(id: ProjectId, change: (Project) -> Project) {
        val project = projects.value?.get(id) ?: return
        writeProject(change(project).copy(updatedAtMillis = clock()))
    }

    private fun writeProject(project: Project) {
        writeAtomically(
            File(dir(project.id), PROJECT),
            json.encodeToString(Project.serializer(), project)
        )
        projects.update { (it ?: emptyMap()) + (project.id to project) }
    }

    private fun writeAtomically(file: File, text: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        check(temp.renameTo(file) || (file.delete() && temp.renameTo(file))) {
            "Could not write $file"
        }
    }

    companion object {
        private const val PROJECT = "project.json"
        private const val TRANSCRIPT = "transcript.jsonl"
        private const val CONVERSATION = "conversation.json"

        /** The generated app of project [id] under store [root], which the runner serves. */
        fun siteDir(root: File, id: ProjectId) = File(File(root, id.value), "site")
    }
}
