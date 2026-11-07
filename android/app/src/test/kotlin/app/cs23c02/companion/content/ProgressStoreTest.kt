package app.cs23c02.companion.content

import app.cs23c02.companion.api.ApiClient
import app.cs23c02.companion.api.ProgressSummary
import app.cs23c02.companion.api.ResumePoint
import app.cs23c02.companion.auth.InMemoryTokenStore
import app.cs23c02.companion.auth.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProgressStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ids = listOf("L1", "L2", "L3", "L4")
    private fun store() = ProgressStore(File(tmp.root, "progress.json"))

    @Test fun resumeRules() {
        assertEquals("L1", ProgressStore.resume(ids, emptySet(), null))                 // start
        assertEquals("L3", ProgressStore.resume(ids, setOf("L1", "L2"), null))          // next after the furthest done
        assertEquals("L2", ProgressStore.resume(ids, setOf("L1"), "L2"))                // the open lesson
        assertEquals("L2", ProgressStore.resume(ids, setOf("L1", "L4"), "L4"))          // done lessons don't resume; gap filled
        assertNull(ProgressStore.resume(ids, ids.toSet(), "L4"))                        // finished
        assertEquals("L1", ProgressStore.resume(ids, emptySet(), "GONE"))               // retired lesson id
    }

    @Test fun eventsAreDedupedAndPersisted() {
        val s = store()
        s.opened("L1"); s.opened("L1"); s.completed("L1"); s.completed("L1"); s.position("L1", 420)
        assertEquals(listOf("lesson_opened", "lesson_completed"), s.state.pending.map { it.kind })
        val again = store()
        assertEquals(setOf("L1"), again.state.completed)
        assertEquals(420, again.state.positions["L1"])
        assertEquals(2, again.state.pending.map { it.id }.toSet().size)
    }

    @Test fun mergeIsAUnionAndAdoptsTheServersOpenLesson() {
        val s = store()
        s.completed("L1")
        s.merge(ProgressSummary("ed", completed = listOf("L2"), resume = ResumePoint("L3", reason = "in_progress")))
        assertEquals(setOf("L1", "L2"), s.state.completed)
        assertEquals("L3", s.state.lastOpened)
    }

    @Test fun flushSendsAndClearsOnlyAnsweredEvents() = runBlocking {
        val server = MockWebServer().apply { start() }
        val tokens = InMemoryTokenStore(Tokens("A", "R", Long.MAX_VALUE))
        val api = ApiClient(server.url("/").toString(), tokens, OkHttpClient(), Dispatchers.IO)
        val s = store()
        s.opened("L1"); s.completed("L1")
        val (a, b) = s.state.pending.map { it.id }
        server.enqueue(MockResponse().setBody("""{"results":[{"id":"$a","status":"accepted"},{"id":"$b","status":"duplicate"}]}"""))
        s.flush(api, "ed")
        val sent = server.takeRequest()
        assertEquals("PUT", sent.method)
        val body = sent.body.readUtf8()
        assertTrue(body.contains("\"kind\":\"lesson_opened\"") && body.contains("\"edition\":\"ed\"") && !body.contains("user"))
        assertTrue(s.state.pending.isEmpty())

        s.completed("L2")
        server.shutdown()
        try { s.flush(api, "ed") } catch (_: app.cs23c02.companion.api.ApiError) {}
        assertEquals(1, s.state.pending.size) // offline: kept for later
    }
}
