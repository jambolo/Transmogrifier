package io.github.jambolo.transmogrifier.project

import io.github.jambolo.transmogrifier.llm.ProviderHistory
import io.github.jambolo.transmogrifier.llm.ProviderId
import io.github.jambolo.transmogrifier.llm.TurnInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectArchiveTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val store by lazy { FileProjectStore(temp.root) }
    private val archive by lazy { ProjectArchive(store) }
    private val defaults = ImportDefaults(ProviderId.OPENAI, "gpt")

    private val conversation = Conversation(
        ProviderHistory(
            ProviderId.ANTHROPIC,
            listOf(
                buildJsonObject {
                    put("type", "thinking")
                    put("signature", "opaque+/=")
                }
            )
        ),
        TurnInput(text = "again"),
        instructions = "Use large text"
    )

    private suspend fun sample(): Project {
        val project = store.create("Tips", ProviderId.ANTHROPIC, "claude-opus-5-5")
        store.appendTranscript(project.id, TranscriptEntry.Prompt("Make it"))
        store.appendTranscript(project.id, TranscriptEntry.Reply("Done"))
        store.saveConversation(project.id, conversation)
        val workspace = store.workspace(project.id)
        workspace.write("index.html", "<p>héllo</p>")
        workspace.write("js/app.js", "console.log(1)")
        return project
    }

    private suspend fun backup(id: ProjectId) =
        ByteArrayOutputStream().also { archive.exportBackup(id, it) }.toByteArray()

    private suspend fun read(bytes: ByteArray, name: String = "Fallback") =
        archive.read(ByteArrayInputStream(bytes), name)

    private suspend fun files(id: ProjectId): Map<String, String> {
        val workspace = store.workspace(id)
        return workspace.listFiles().associate {
            it.path to (workspace.read(it.path) as WorkspaceResult.Ok).value
        }
    }

    private fun zip(entries: Map<String, String>) = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun names(bytes: ByteArray) = ZipInputStream(ByteArrayInputStream(bytes)).use {
        generateSequence { it.nextEntry }.map { entry -> entry.name }.toList()
    }

    private suspend fun assertRejected(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected ArchiveException")
        } catch (_: ArchiveException) {
        }
    }

    @Test
    fun backupRoundTripsWithConversation() = runTest {
        val original = sample()
        val contents = read(backup(original.id))
        assertTrue(contents.hasConversation)

        val copy = archive.import(contents, defaults, withConversation = true)

        assertNotEquals(original.id, copy.id)
        assertEquals("Tips", copy.name)
        assertEquals(ProviderId.ANTHROPIC, copy.provider)
        assertEquals("claude-opus-5-5", copy.model)
        assertEquals(store.transcript(original.id).first(), store.transcript(copy.id).first())
        assertEquals(conversation, store.conversation(copy.id))
        assertEquals(files(original.id), files(copy.id))
    }

    @Test
    fun importWithoutConversationStartsFresh() = runTest {
        val original = sample()
        val copy = archive.import(read(backup(original.id)), defaults, withConversation = false)

        assertNull(store.conversation(copy.id))
        assertEquals(
            store.transcript(original.id).first() +
                TranscriptEntry.Notice(NoticeKind.IMPORTED_WITHOUT_CONVERSATION),
            store.transcript(copy.id).first()
        )
        assertEquals(files(original.id), files(copy.id))
    }

    @Test
    fun siteExportHoldsOnlyTheSite() = runTest {
        val project = sample()
        val bytes = ByteArrayOutputStream().also { archive.exportSite(project.id, it) }
        assertEquals(listOf("index.html", "js/app.js"), names(bytes.toByteArray()).sorted())
    }

    @Test
    fun siteZipImportsAsNewProject() = runTest {
        val bytes = zip(
            mapOf(
                "My App/index.html" to "<p>hi</p>",
                "My App/style.css" to "p {}",
                "__MACOSX/My App/._index.html" to "junk",
                "My App/.DS_Store" to "junk"
            )
        )
        val contents = read(bytes, "My App")
        assertFalse(contents.hasConversation)

        val project = archive.import(contents, defaults, withConversation = true)

        assertEquals("My App", project.name)
        assertEquals(ProviderId.OPENAI, project.provider)
        assertEquals(
            mapOf("index.html" to "<p>hi</p>", "style.css" to "p {}"),
            files(project.id)
        )
        assertEquals(emptyList<TranscriptEntry>(), store.transcript(project.id).first())
    }

    @Test
    fun rejectsPathsOutsideTheSite() = runTest {
        val contents = read(zip(mapOf("index.html" to "", "../escape.html" to "x")))
        assertRejected { archive.import(contents, defaults, withConversation = false) }
        assertEquals(emptyList<ProjectSummary>(), store.projects().first())
        assertFalse(temp.root.resolve("escape.html").exists())
    }

    @Test
    fun rejectsDisallowedFilesWithoutLeavingAProject() = runTest {
        val contents = read(zip(mapOf("index.html" to "", "run.sh" to "rm -rf /")))
        assertRejected { archive.import(contents, defaults, withConversation = false) }
        assertEquals(emptyList<ProjectSummary>(), store.projects().first())
    }

    @Test
    fun rejectsInvalidArchives() = runTest {
        assertRejected { read("not a zip".toByteArray()) }
        assertRejected { read(zip(mapOf("readme.txt" to "no index"))) }
        assertRejected {
            read(
                zip(
                    mapOf(
                        ProjectArchive.MANIFEST to
                            """{"format":"transmogrifier-project","version":99}"""
                    )
                )
            )
        }
        assertRejected {
            read(
                zip(
                    mapOf(
                        ProjectArchive.MANIFEST to
                            """{"format":"transmogrifier-project","version":1}""",
                        "project.json" to """{"id":"x","name":"n","provider":"NOPE"}"""
                    )
                )
            )
        }
    }

    @Test
    fun rejectsConversationForAnotherProvider() = runTest {
        val project = sample()
        store.saveConversation(project.id, Conversation(ProviderHistory(ProviderId.OPENAI)))
        // A project's conversation always uses its provider; a mismatch means a damaged backup.
        val bytes = backup(project.id)
        assertRejected { read(bytes) }
    }
}
