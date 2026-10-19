package com.example.transmogrifier.project

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Keeps project metadata in memory; workspaces are real directories under [root]. */
class FakeProjectStore(
    private val root: File,
    private val limits: WorkspaceLimits = WorkspaceLimits(),
    private val clock: () -> Long = System::currentTimeMillis
) : ProjectStore {
    private val state = MutableStateFlow<Map<ProjectId, Project>>(emptyMap())

    override fun projects(): Flow<List<ProjectSummary>> = state.map { projects ->
        projects.values.sortedByDescending { it.updatedAtMillis }.map { it.summary() }
    }

    override suspend fun create(name: String): Project {
        val now = clock()
        val project =
            Project(
                ProjectId(UUID.randomUUID().toString()),
                name,
                createdAtMillis = now,
                updatedAtMillis = now
            )
        state.update { it + (project.id to project) }
        return project
    }

    override suspend fun workspace(id: ProjectId): Workspace {
        require(id in state.value) { "Unknown project $id" }
        return Workspace(File(root, "${id.value}/site"), limits)
    }
}
