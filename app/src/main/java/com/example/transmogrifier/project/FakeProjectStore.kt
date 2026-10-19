package com.example.transmogrifier.project

import com.example.transmogrifier.llm.ProviderId
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Keeps project metadata and transcripts in memory; workspaces are real directories under [root]. */
class FakeProjectStore(
    private val root: File,
    private val limits: WorkspaceLimits = WorkspaceLimits(),
    private val clock: () -> Long = System::currentTimeMillis
) : ProjectStore {
    private val projects = MutableStateFlow<Map<ProjectId, Project>>(emptyMap())
    private val transcripts = MutableStateFlow<Map<ProjectId, List<TranscriptEntry>>>(emptyMap())
    private val conversations = MutableStateFlow<Map<ProjectId, Conversation>>(emptyMap())

    override fun projects(): Flow<List<ProjectSummary>> = projects.map { projects ->
        projects.values.sortedByDescending { it.updatedAtMillis }.map { it.summary() }
    }

    override fun project(id: ProjectId): Flow<Project?> =
        projects.map { it[id] }.distinctUntilChanged()

    override suspend fun create(name: String, provider: ProviderId, model: String): Project {
        val now = clock()
        val id = ProjectId(UUID.randomUUID().toString())
        val project = Project(id, name, provider, model, now, now)
        projects.update { it + (id to project) }
        return project
    }

    override suspend fun rename(id: ProjectId, name: String) = touch(id) { it.copy(name = name) }

    override suspend fun delete(id: ProjectId) {
        projects.update { it - id }
        transcripts.update { it - id }
        withContext(Dispatchers.IO) { File(root, id.value).deleteRecursively() }
    }

    override fun transcript(id: ProjectId): Flow<List<TranscriptEntry>> =
        transcripts.map { it[id].orEmpty() }.distinctUntilChanged()

    override suspend fun appendTranscript(id: ProjectId, entry: TranscriptEntry) {
        transcripts.update { it + (id to it[id].orEmpty() + entry) }
        touch(id) { it }
    }

    override suspend fun conversation(id: ProjectId): Conversation? = conversations.value[id]

    override suspend fun saveConversation(id: ProjectId, conversation: Conversation) {
        conversations.update { it + (id to conversation) }
    }

    override suspend fun workspace(id: ProjectId): Workspace {
        require(id in projects.value) { "Unknown project $id" }
        return Workspace(File(root, "${id.value}/site"), limits)
    }

    private fun touch(id: ProjectId, change: (Project) -> Project) = projects.update { projects ->
        val project = projects[id] ?: return@update projects
        projects + (id to change(project).copy(updatedAtMillis = clock()))
    }
}
