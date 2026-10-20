package io.github.jambolo.transmogrifier.project

import io.github.jambolo.transmogrifier.llm.ProviderHistory
import io.github.jambolo.transmogrifier.llm.ProviderId
import io.github.jambolo.transmogrifier.llm.TurnInput
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
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
    @SerialName("prompt")
    data class Prompt(val text: String, val fixErrors: Boolean = false) : TranscriptEntry

    @Serializable
    @SerialName("reply")
    data class Reply(val text: String) : TranscriptEntry

    /** One executed tool call; [error] is set when the tool failed. */
    @Serializable
    @SerialName("tool")
    data class Tool(val name: String, val path: String?, val error: String? = null) :
        TranscriptEntry

    @Serializable
    @SerialName("usage")
    data class Usage(val inputTokens: Long, val outputTokens: Long, val cacheReadTokens: Long) :
        TranscriptEntry

    @Serializable
    @SerialName("notice")
    data class Notice(val kind: NoticeKind, val detail: String? = null) : TranscriptEntry
}

@Serializable
enum class NoticeKind {
    /** The user pressed Stop; the conversation can continue. */
    STOPPED,
    ITERATION_LIMIT,

    /** Output hit the token limit before the model finished. */
    TRUNCATED,
    REFUSED,

    /** The API key is missing or was rejected. */
    AUTH_FAILED,
    FAILED,

    /** The project's provider is disabled in this version of the app. */
    PROVIDER_UNAVAILABLE
}

/**
 * The provider conversation for one project: append-only [history] plus [pending] input that has not been sent
 * successfully yet (a turn that failed or was stopped resumes from it). [instructions] are the user's standing
 * instructions as last delivered in [history].
 */
@Serializable
data class Conversation(
    val history: ProviderHistory,
    val pending: TurnInput? = null,
    val instructions: String = "",
    val network: Boolean = false
)

interface ProjectStore {
    fun projects(): Flow<List<ProjectSummary>>

    fun project(id: ProjectId): Flow<Project?>

    suspend fun create(name: String, provider: ProviderId, model: String): Project

    suspend fun rename(id: ProjectId, name: String)

    suspend fun delete(id: ProjectId)

    fun transcript(id: ProjectId): Flow<List<TranscriptEntry>>

    suspend fun appendTranscript(id: ProjectId, entry: TranscriptEntry)

    suspend fun conversation(id: ProjectId): Conversation?

    /**
     * @throws IllegalArgumentException if [conversation] does not extend the stored history (history is
     *   append-only; a conversation with a different provider replaces it).
     */
    suspend fun saveConversation(id: ProjectId, conversation: Conversation)

    /**
     * Sandboxed file access to one project's `site/` directory.
     *
     * @throws IllegalArgumentException if the project does not exist.
     */
    suspend fun workspace(id: ProjectId): Workspace
}
