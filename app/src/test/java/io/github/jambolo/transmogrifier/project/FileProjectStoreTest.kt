package io.github.jambolo.transmogrifier.project

import io.github.jambolo.transmogrifier.llm.ProviderHistory
import io.github.jambolo.transmogrifier.llm.ProviderId
import io.github.jambolo.transmogrifier.llm.TurnInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileProjectStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = 1_000L
    private fun store() = FileProjectStore(temp.root, clock = { now++ })

    @Test
    fun persistsAcrossInstances() = runTest {
        val first = store()
        val project = first.create("Tip calculator", ProviderId.ANTHROPIC, "claude-opus-5-5")
        first.appendTranscript(project.id, TranscriptEntry.Prompt("Make it"))
        first.appendTranscript(project.id, TranscriptEntry.Notice(NoticeKind.STOPPED))
        val conversation = Conversation(
            ProviderHistory(ProviderId.ANTHROPIC, listOf(JsonPrimitive("turn"))),
            TurnInput(text = "again")
        )
        first.saveConversation(project.id, conversation)
        first.rename(project.id, "Tips")
        first.workspace(project.id).write("index.html", "<p>hi</p>")

        val second = store()
        assertEquals(listOf("Tips"), second.projects().first().map { it.name })
        assertEquals(
            listOf(TranscriptEntry.Prompt("Make it"), TranscriptEntry.Notice(NoticeKind.STOPPED)),
            second.transcript(project.id).first()
        )
        assertEquals(conversation, second.conversation(project.id))
        assertEquals(1, second.workspace(project.id).listFiles().size)
    }

    @Test
    fun historyIsAppendOnly() = runTest {
        val store = store()
        val id = store.create("App", ProviderId.ANTHROPIC, "m").id
        val one = ProviderHistory(ProviderId.ANTHROPIC, listOf(JsonPrimitive("a")))
        store.saveConversation(id, Conversation(one))
        store.saveConversation(id, Conversation(one.copy(items = one.items + JsonPrimitive("b"))))
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.saveConversation(
                    id,
                    Conversation(one.copy(items = listOf(JsonPrimitive("x"))))
                )
            }
        }
        // A different provider starts a new conversation.
        store.saveConversation(id, Conversation(ProviderHistory(ProviderId.OPENAI)))
        assertEquals(ProviderId.OPENAI, store.conversation(id)!!.history.provider)
    }

    @Test
    fun deleteRemovesEverything() = runTest {
        val store = store()
        val keep = store.create("Keep", ProviderId.OPENAI, "m")
        val gone = store.create("Gone", ProviderId.OPENAI, "m")
        store.delete(gone.id)
        assertEquals(listOf(keep.id), store().projects().first().map { it.id })
        assertNull(store.project(gone.id).first())
        assertTrue(runCatching { store.workspace(gone.id) }.isFailure)
    }
}
