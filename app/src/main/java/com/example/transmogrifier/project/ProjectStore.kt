package com.example.transmogrifier.project

import com.example.transmogrifier.llm.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ProjectId(val value: String)

@Serializable
data class Project(
    val id: ProjectId,
    val name: String,
    val provider: ProviderId? = null,
    val model: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class ProjectSummary(
    val id: ProjectId,
    val name: String,
    val provider: ProviderId?,
    val updatedAtMillis: Long
)

fun Project.summary() = ProjectSummary(id, name, provider, updatedAtMillis)

interface ProjectStore {
    fun projects(): Flow<List<ProjectSummary>>

    suspend fun create(name: String): Project

    /** Sandboxed file access to one project's `site/` directory. */
    suspend fun workspace(id: ProjectId): Workspace
}
