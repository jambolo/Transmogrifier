package io.github.jambolo.transmogrifier.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jambolo.transmogrifier.project.ProjectId
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Loads a fixture site in a real WebView. Run locally on an emulator. */
@RunWith(AndroidJUnit4::class)
class SiteBrowserTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val siteDir = File(instrumentation.targetContext.cacheDir, "site-${UUID.randomUUID()}")
    private lateinit var browser: SiteBrowser

    @Before
    fun setUp() {
        site("index.html", page("Fixture"))
        site(
            "js/app.js",
            """
            console.log('started');
            fetch('https://example.com/').then(
              () => { document.title = 'leaked'; },
              () => { console.warn('network blocked'); }
            );
            function fail() { throw new TypeError('boom'); }
            setTimeout(fail, 0);
            """.trimIndent()
        )
        browser = onMain {
            SiteBrowser(instrumentation.targetContext, ProjectId("${UUID.randomUUID()}"), siteDir)
        }
        onMain { browser.load() }
    }

    @After
    fun tearDown() {
        onMain { browser.destroy() }
        siteDir.deleteRecursively()
    }

    @Test
    fun loadsSiteAndCapturesConsole() {
        awaitTitle("Fixture")
        val console = awaitConsole { messages ->
            messages.any { it.message.contains("boom") } &&
                messages.any { it.message == "network blocked" }
        }

        val log = console.single { it.message == "started" }
        assertEquals(ConsoleMessage.Level.LOG, log.level)
        assertEquals("js/app.js", log.source)

        // The reporter replaces Chromium's own "Uncaught" line, so the error appears once, with its stack.
        val error = console.single { it.message.contains("boom") }
        assertEquals(ConsoleMessage.Level.ERROR, error.level)
        assertEquals("js/app.js", error.source)
        assertTrue(error.stack.orEmpty(), error.stack.orEmpty().contains("fail"))
        assertTrue(console.errorLog().orEmpty().contains("TypeError: boom (js/app.js:6)"))
    }

    @Test
    fun reloadShowsEditedFiles() {
        awaitTitle("Fixture")
        site("index.html", page("Edited"))
        onMain { browser.reload() }
        awaitTitle("Edited")
    }

    @Test
    fun missingFileIsReported() {
        awaitTitle("Fixture")
        onMain { browser.webView.loadUrl("${browser.origin}/missing.html") }
        val console = awaitConsole { messages ->
            messages.any { it.message.startsWith("Could not load missing.html") }
        }
        assertEquals(
            ConsoleMessage.Level.ERROR,
            console.first { it.message.startsWith("Could not load") }.level
        )
    }

    @Test
    fun speaksAndPlaysAudioWithoutATap() {
        awaitTitle("Fixture")
        site(
            "js/app.js",
            """
            const audio = new AudioContext();
            setTimeout(() => console.log('audio ' + audio.state), 500);
            speechSynthesis.onvoiceschanged = () => {
              console.log('voices ' + (speechSynthesis.getVoices().length > 0));
            };
            const utterance = new SpeechSynthesisUtterance('Test');
            utterance.volume = 0.2;
            utterance.onstart = () => console.log('speech started');
            utterance.onend = () => console.log('speech ended');
            utterance.onerror = (event) => console.log('speech error ' + event.error);
            speechSynthesis.speak(utterance);
            """.trimIndent()
        )
        onMain { browser.reload() }
        val console = awaitConsole { messages ->
            messages.any { it.message.startsWith("audio ") } &&
                messages.any { it.message.startsWith("voices ") } &&
                messages.any { it.message.matches(SPEECH_DONE) }
        }.map { it.message }

        assertTrue(console.toString(), "audio running" in console)
        assertTrue(console.toString(), "voices true" in console)
        assertEquals(
            listOf("speech started", "speech ended"),
            console.filter { it.startsWith("speech") }
        )
    }

    @Test
    fun cancelEndsQueuedSpeech() {
        awaitTitle("Fixture")
        site(
            "js/app.js",
            """
            for (const text of ['One', 'Two']) {
              const utterance = new SpeechSynthesisUtterance(text);
              utterance.onend = () => console.log(text + ' ended');
              utterance.onerror = (event) => console.log(text + ' ' + event.error);
              speechSynthesis.speak(utterance);
            }
            speechSynthesis.cancel();
            console.log('speaking ' + speechSynthesis.speaking);
            """.trimIndent()
        )
        onMain { browser.reload() }
        val console = awaitConsole { messages -> messages.any { it.message.startsWith("Two ") } }
            .map { it.message }
            .filter { it.matches(Regex("(speaking|One|Two) .*")) }

        assertEquals(listOf("speaking false", "One canceled", "Two canceled"), console)
    }

    private fun page(title: String) = "<!doctype html><html><head><title>$title</title>" +
        "<script src=\"js/app.js\"></script></head><body></body></html>"

    private fun site(path: String, content: String) =
        File(siteDir, path).apply { parentFile!!.mkdirs() }.writeText(content)

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun awaitConsole(predicate: (List<ConsoleMessage>) -> Boolean) =
        runBlocking { withTimeout(TIMEOUT_MILLIS) { browser.console.first(predicate) } }

    private fun awaitTitle(expected: String) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (onMain { browser.webView.title } != expected) {
            check(System.currentTimeMillis() < deadline) {
                "Title is ${onMain { browser.webView.title }}"
            }
            Thread.sleep(50)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
        val SPEECH_DONE = Regex("speech (ended|error .*)")
    }
}
