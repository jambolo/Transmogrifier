package io.github.jambolo.transmogrifier.agent

import io.github.jambolo.transmogrifier.project.ProjectId
import kotlinx.coroutines.flow.Flow

/**
 * Live progress of one generation. Durable results (replies, tool calls, usage, notices) are appended
 * to the project transcript by the engine; these events only drive the in-progress view.
 */
sealed interface GenerationEvent {
    data class TextDelta(val text: String) : GenerationEvent

    data class ToolRunning(val name: String, val path: String?) : GenerationEvent

    /** The streamed text and running tool so far are now in the transcript. */
    data object TurnPersisted : GenerationEvent

    /** A turn failed with a retryable error; it restarts after [delaySeconds] and its partial output is discarded. */
    data class Retrying(val attempt: Int, val delaySeconds: Int, val reason: String) :
        GenerationEvent
}

interface GenerationEngine {
    /**
     * Runs one prompt to completion, or resumes pending input from a stopped or failed generation when [prompt]
     * is null. Cancelling the collector stops generation and records a
     * [io.github.jambolo.transmogrifier.project.NoticeKind.STOPPED] notice.
     */
    fun generate(
        projectId: ProjectId,
        prompt: String?,
        fixErrors: Boolean = false
    ): Flow<GenerationEvent>
}
