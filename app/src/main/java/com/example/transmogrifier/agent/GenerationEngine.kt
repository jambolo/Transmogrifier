package com.example.transmogrifier.agent

import com.example.transmogrifier.project.ProjectId
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
}

interface GenerationEngine {
    /**
     * Runs one prompt to completion. Cancelling the collector stops generation and records a
     * [com.example.transmogrifier.project.NoticeKind.STOPPED] notice.
     */
    fun generate(
        projectId: ProjectId,
        prompt: String,
        fixErrors: Boolean = false
    ): Flow<GenerationEvent>
}
