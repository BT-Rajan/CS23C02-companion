package app.cs23c02.companion.api

import app.cs23c02.companion.auth.InMemoryTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Contract test against a REAL backend (skipped unless API_IT_URL is set). It proves the app and the server agree on
 * paths, field names, status codes and error shapes. Run it with android/scripts/contract-test.sh, which starts the backend
 * with the console mail backend (API_IT_MAILLOG = its log) and creates one released activation code (API_IT_CODE).
 */
class LiveBackendContractTest {
    private val url = System.getenv("API_IT_URL")
    private val mailLog = System.getenv("API_IT_MAILLOG")
    private val activationCode = System.getenv("API_IT_CODE")

    private fun lastCode(email: String, kind: String): String {
        repeat(50) {
            val text = File(mailLog).readText()
            val re = Regex("to=${Regex.escape(email)} subject=[^\\n]*\\n.*$kind code is (\\d{6})")
            re.findAll(text).lastOrNull()?.let { return it.groupValues[1] }
            Thread.sleep(100)
        }
        error("no $kind email for $email")
    }

    @Test fun fullJourneyAgainstRealServer() = runBlocking {
        assumeTrue("API_IT_URL not set", !url.isNullOrBlank())
        var now = System.currentTimeMillis()
        val store = InMemoryTokenStore()
        val api = ApiClient(url, store, OkHttpClient(), Dispatchers.IO) { now }
        val email = "it-${System.nanoTime()}@example.com"
        val pw = "a long test passphrase"

        // Weak password is refused by the server with the same rule the app checks locally.
        try { api.register(email, "short"); fail() } catch (e: ApiError) { assertEquals("weak_password", e.code) }

        api.register(email, pw)
        try { api.login(email, pw, "IT"); fail() } catch (e: ApiError) { assertEquals(403, e.status); assertEquals("email_unverified", e.code) }
        try { api.verifyEmail(email, "000000", "IT"); fail() } catch (e: ApiError) { assertEquals("bad_code", e.code) }
        api.verifyEmail(email, lastCode(email, "verification"), "IT contract test")
        assertTrue(api.me().entitlements.isEmpty())

        // Client-side format check and server agree: wrong check character is "bad_format" on the server too.
        try { api.activate("Z83G-BHAB-JD4T-H5BC"); fail() } catch (e: ApiError) { assertEquals("bad_format", e.code); assertTrue(e.ref != null) }
        val ent = api.activate(activationCode)
        assertEquals(ent.editionId, api.me().entitlements.single().editionId)
        // Re-using the same code gets the generic refusal (the server never reveals a code's state), not "already activated".
        try { api.activate(activationCode); fail() } catch (e: ApiError) { assertEquals(400, e.status); assertEquals("activation_failed", e.code) }

        // Access token "expires": the client refreshes, the server rotates the refresh token.
        val before = store.load()!!.refresh
        now += 3_600_000
        api.me()
        assertNotEquals(before, store.load()!!.refresh)

        // A2: download, verify and read the signed package; progress round-trips and survives a "reinstall".
        val contentKey = System.getenv("API_IT_CONTENT_PUBKEY")
        check(!contentKey.isNullOrBlank()) { "API_IT_CONTENT_PUBKEY not set: run android/scripts/contract-test.sh" }
        run {
            val tmp = java.nio.file.Files.createTempDirectory("it").toFile()
            fun repo() = app.cs23c02.companion.content.ReaderRepository(api,
                app.cs23c02.companion.content.ContentStore(java.io.File(tmp, "pkg"), contentKey, "0.1.0"),
                app.cs23c02.companion.content.ProgressStore(java.io.File(tmp, "p.json")))
            val r = repo()
            assertEquals(app.cs23c02.companion.content.ReaderRepository.SyncOutcome.Updated, r.sync(ent.editionId))
            val pkg = r.content.current()!!
            assertEquals(listOf("LSN-0001", "LSN-0002", "LSN-0003"), pkg.manifest.nav.units.flatMap { u -> u.topics.flatMap { it.lessons } })
            assertEquals("What is a variable?", pkg.lesson("LSN-0001")!!.title)
            assertEquals(app.cs23c02.companion.content.ReaderRepository.SyncOutcome.UpToDate, r.sync(ent.editionId))

            r.progress.opened("LSN-0001"); r.progress.completed("LSN-0001"); r.progress.opened("LSN-0002")
            val sent = r.progress.state.pending.toList()
            r.pushProgress(ent.editionId)
            assertTrue(r.progress.state.pending.isEmpty())
            val server = api.progress(ent.editionId)
            assertEquals(listOf("LSN-0001"), server.completed)
            assertEquals("LSN-0002", server.resume!!.lessonId)
            assertEquals("in_progress", server.resume!!.reason)
            // Replaying the very same events (same UUIDs) is harmless: the server reports them as duplicates.
            assertTrue(api.putProgress(ent.editionId, sent).all { it.status == "duplicate" })
            // A lesson id that is not in the published package is reported, not stored.
            assertEquals("unknown_content", api.putProgress(ent.editionId, listOf(app.cs23c02.companion.api.ProgressEventOut(
                java.util.UUID.randomUUID().toString(), "lesson_opened", "LSN-9999"))).single().status)

            // A5: practice is checked on the server, one question at a time.
            val trace = pkg.item("ITM-0004")!!
            assertEquals("trace", trace.type); assertTrue(trace.code!!.contains("print('odd')"))
            val w1 = r.checkPractice("ITM-0001", "a", ent.editionId)
            assertTrue(!w1.correct && !w1.revealed && w1.answer == null && w1.why == "That would be ==.")
            val w2 = r.checkPractice("ITM-0001", "c", ent.editionId)
            assertTrue(w2.revealed && w2.answer == "b" && w2.explanation!!.startsWith("FIXTURE-EXPLAIN-1"))
            assertTrue(r.checkPractice("ITM-0004", "b", ent.editionId).correct)
            try { r.checkPractice("ITM-0005", "a", ent.editionId); fail() }        // assessment item: not checkable here
            catch (e: ApiError) { assertEquals(404, e.status); assertEquals("not_found", e.code) }
            assertTrue(api.progress(ent.editionId).completed == listOf("LSN-0001"))  // practice never counts as progress

            // A6: exercises are graded by the separate sandbox service, reached by the backend over signed HTTP.
            val exr = pkg.exercise("EXR-0001")!!
            assertEquals(listOf(app.cs23c02.companion.content.PublicTest("4\n", "8\n")), exr.tests)   // hidden tests never shipped
            val bad = r.submitExercise(exr, "n = int(input())\nprint(n + 4)\n", ent.editionId)
            assertTrue(!bad.passed && bad.public.single().passed && bad.hidden.passed == 0 && bad.hidden.total == 2)
            assertEquals("wrong output", bad.hidden.firstFailure)
            assertEquals(1, bad.help.hintsUnlocked)
            val h1 = r.exerciseHelp(exr, ent.editionId)
            assertTrue(h1.hints.single().startsWith("FIXTURE-HINT-1") && h1.solution == null)
            val good = r.submitExercise(exr, "n = int(input())\nprint(n * 2)\n", ent.editionId)
            assertTrue(good.passed && good.hidden.passed == 2 && good.help.solutionUnlocked)
            assertTrue(r.exerciseHelp(exr, ent.editionId).solution!!.code.contains("FIXTURE-REFERENCE-SOLUTION-1"))
            val runaway = r.submitExercise(exr, "while True:\n    pass\n", ent.editionId)
            assertEquals("timeout", runaway.public.single().status)
            assertEquals(listOf("EXR-0001"), api.progress(ent.editionId).exercisesPassed)

            // A7: the unit assessment is a server-owned state machine.
            val unitAsm = pkg.manifest.nav.units.single().assessment!!
            assertEquals("ASM-0002", unitAsm)
            val att = r.startAssessment(unitAsm, ent.editionId)
            assertEquals(3, att.questions.size)
            assertTrue(att.review.isEmpty() && att.score == null)                          // nothing about correctness yet
            assertEquals(att.attemptId, r.startAssessment(unitAsm, ent.editionId).attemptId)   // resume, not a second attempt
            val key = mapOf("ITM-0005" to "a", "ITM-0006" to "b", "ITM-0007" to "b")
            r.saveAnswers(att.attemptId, 1, key - "ITM-0007")
            try { r.saveAnswers(att.attemptId, 1, key); fail() } catch (e: ApiError) { assertEquals("stale_save", e.code) }
            val rid = java.util.UUID.randomUUID().toString()
            val done = r.submitAssessment(att.attemptId, rid, 2, key)
            assertTrue(done.submitted && done.score == 3 && done.maxScore == 3 && done.review.all { it.correct })
            assertEquals(done.submittedAt, r.submitAssessment(att.attemptId, rid, 3, emptyMap()).submittedAt)   // idempotent
            try { r.startAssessment(unitAsm, ent.editionId); fail() } catch (e: ApiError) { assertEquals("attempt_cooldown", e.code) }
            assertTrue(r.assessmentHistory(unitAsm, ent.editionId).nextAttemptAt != null)
            assertEquals(3, api.progress(ent.editionId).assessments.single().bestScore)

            tmp.resolve("p.json").delete()                       // "reinstall": local progress gone
            val fresh = repo()
            fresh.sync(ent.editionId)
            assertEquals(setOf("LSN-0001"), fresh.progress.state.completed)
            assertEquals("LSN-0002", fresh.progress.state.lastOpened)
            tmp.deleteRecursively()
        }

        api.logout()
        assertNull(store.load())
        try { api.login(email, "wrong passphrase!", "IT"); fail() } catch (e: ApiError) { assertEquals("bad_credentials", e.code) }

        // Recovery: reset by emailed code, then sign in with the new password.
        api.forgot(email)
        api.reset(email, lastCode(email, "password reset"), "another long passphrase")
        api.login(email, "another long passphrase", "IT")
        assertEquals(email, api.me().email)
    }

    /** K9 (ADR 0004): many sign-ins in a day trigger "confirm it's you" by a code sent to the account's address; never a lock. */
    @Test fun anomalyReverificationAgainstRealServer() = runBlocking {
        assumeTrue("API_IT_URL not set", !url.isNullOrBlank())
        val store = InMemoryTokenStore()
        val api = ApiClient(url, store, OkHttpClient(), Dispatchers.IO)
        val email = "it-share-${System.nanoTime()}@example.com"
        val pw = "a long test passphrase"
        api.register(email, pw)
        api.verifyEmail(email, lastCode(email, "verification"), "phone 1")        // sign-in 1
        repeat(5) { api.login(email, pw, "phone ${it + 2}") }                    // sign-ins 2..6 from one address: the 6th triggers
        assertTrue(api.me().reverifyRequired)
        try { api.manifest(null); fail() } catch (e: ApiError) { assertEquals(403, e.status); assertEquals("reverify_required", e.code) }
        val sent = api.sendReverifyCode()
        assertEquals("code_sent", sent.status)
        assertTrue(sent.emailHint!!.startsWith("i***@"))
        try { api.reverify("000000"); fail() } catch (e: ApiError) { assertEquals("bad_code", e.code) }
        api.reverify(lastCode(email, "Your"))
        assertTrue(!api.me().reverifyRequired)
        assertEquals("not_needed", api.sendReverifyCode().status)
    }
}
