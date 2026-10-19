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
    val provider: ProviderId,
    val model: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class ProjectSummary(
    val id: ProjectId,
    val name: String,
    val provider: ProviderId,
    val updatedAtMillis: Long
)

fun Project.summary() = ProjectSummary(id, name, provider, updatedAtMillis)

/**
 * Provider-neutral record of a conversation for display. The provider's own wire-format history is
 * opaque, so the workspace renders this instead.
 */
@Serializable
sealed interface TranscriptEntry {
    @Serializable
    data class Prompt(val text: String, val fixErrors: Boolean = false) : TranscriptEntry

    @Serializable
    data class Reply(val text: String) : TranscriptEntry

    /** One executed tool call; [error] is set when the tool failed. */
    @Serializable
    data class Tool(val name: String, val path: String?, val error: String? = null) :
        TranscriptEntry

    @Serializable
    data class Usage(val inputTokens: Long, val outputTokens: Long, val cacheReadTokens: Long) :
        TranscriptEntry

    @Serializable
    data class Notice(val kind: NoticeKind, val detail: String? = null) : TranscriptEntry
}

@Serializable
enum class NoticeKind { STOPPED, ITERATION_LIMIT, FAILED }

interface ProjectStore {
    fun projects(): Flow<List<ProjectSummary>>

    fun project(id: ProjectId): Flow<Project?>

    suspend fun create(name: String, provider: ProviderId, model: String): Project

    suspend fun rename(id: ProjectId, name: String)

    suspend fun delete(id: ProjectId)

    fun transcript(id: ProjectId): Flow<List<TranscriptEntry>>

    suspend fun appendTranscript(id: ProjectId, entry: TranscriptEntry)

    /**
     * Sandboxed file access to one project's `site/` directory.
     *
     * @throws IllegalArgumentException if the project does not exist.
     */
    suspend fun workspace(id: ProjectId): Workspace
}
