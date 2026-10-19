package com.example.transmogrifier.project

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var site: File
    private lateinit var workspace: Workspace

    @Before
    fun setUp() {
        site = File(temp.root, "site")
        workspace =
            Workspace(
                site,
                WorkspaceLimits(
                    maxDepth = 3,
                    maxFiles = 3,
                    maxFileBytes = 100,
                    maxTotalBytes = 150,
                    maxPathLength = 40
                )
            )
    }

    private fun assertRejected(result: WorkspaceResult<*>) =
        assertTrue("$result", result is WorkspaceResult.Error)

    @Test
    fun acceptsValidPaths() {
        for (path in listOf(
            "index.html",
            "js/app.js",
            "a/b/c.CSS",
            "data.v2.json",
            "my file.txt"
        )) {
            assertEquals(
                path,
                File(site.canonicalFile, path).canonicalFile,
                (workspace.resolve(path) as WorkspaceResult.Ok).value
            )
        }
    }

    @Test
    fun rejectsInvalidPaths() {
        val invalid =
            listOf(
                "",
                "/index.html",
                "../index.html",
                "a/../index.html",
                "./index.html",
                "a//b.js",
                "a/",
                "a\\b.js",
                "C:/index.html",
                "index.html\u0000.js",
                "a/b/c/d.js",
                "script.sh",
                "noextension",
                ".html",
                "x".repeat(40) + ".js"
            )
        for (path in invalid) assertRejected(workspace.resolve(path))
    }

    @Test
    fun rejectsSymlinkEscape() {
        val outside = temp.newFolder("outside")
        site.mkdirs()
        try {
            Files.createSymbolicLink(File(site, "link").toPath(), outside.toPath())
        } catch (e: Exception) {
            assumeNoException("Symlinks need extra privileges on Windows", e)
        }
        assertRejected(workspace.resolve("link/evil.js"))
    }

    @Test
    fun writeReadListDelete() = runTest {
        assertEquals(WorkspaceResult.Ok(Unit), workspace.write("css/style.css", "body{}"))
        assertEquals(WorkspaceResult.Ok(Unit), workspace.write("index.html", "<p>hi</p>"))
        assertEquals(WorkspaceResult.Ok("body{}"), workspace.read("css/style.css"))
        assertEquals(
            listOf(FileEntry("css/style.css", 6), FileEntry("index.html", 9)),
            workspace.listFiles()
        )

        assertEquals(WorkspaceResult.Ok(Unit), workspace.delete("css/style.css"))
        assertTrue(!File(site, "css").exists())
        assertEquals(listOf(FileEntry("index.html", 9)), workspace.listFiles())
    }

    @Test
    fun missingFilesAreErrors() = runTest {
        assertEquals(emptyList<FileEntry>(), workspace.listFiles())
        assertRejected(workspace.read("index.html"))
        assertRejected(workspace.delete("index.html"))
        assertRejected(workspace.edit("index.html", "a", "b"))
    }

    @Test
    fun enforcesSizeAndCountLimits() = runTest {
        assertRejected(workspace.write("big.txt", "x".repeat(101)))
        assertEquals(WorkspaceResult.Ok(Unit), workspace.write("a.txt", "x".repeat(100)))
        assertRejected(workspace.write("b.txt", "x".repeat(51)))
        // Overwriting counts the replaced file's size only once.
        assertEquals(WorkspaceResult.Ok(Unit), workspace.write("a.txt", "x".repeat(100)))
        assertEquals(WorkspaceResult.Ok(Unit), workspace.write("b.txt", "x"))
        assertEquals(WorkspaceResult.Ok(Unit), workspace.write("c.txt", "x"))
        assertRejected(workspace.write("d.txt", "x"))
        assertEquals(WorkspaceResult.Ok(Unit), workspace.write("c.txt", "y"))
    }

    @Test
    fun editReplacesExactlyOneOccurrence() = runTest {
        workspace.write("app.js", "let a = 1; let b = 2; let b2 = 2;")
        assertEquals(WorkspaceResult.Ok(Unit), workspace.edit("app.js", "a = 1", "a = 3"))
        assertEquals(
            WorkspaceResult.Ok("let a = 3; let b = 2; let b2 = 2;"),
            workspace.read("app.js")
        )
        assertRejected(workspace.edit("app.js", "= 2", "= 4"))
        assertRejected(workspace.edit("app.js", "missing", "x"))
        assertRejected(workspace.edit("app.js", "", "x"))
        assertEquals(
            WorkspaceResult.Ok("let a = 3; let b = 2; let b2 = 2;"),
            workspace.read("app.js")
        )
    }
}
