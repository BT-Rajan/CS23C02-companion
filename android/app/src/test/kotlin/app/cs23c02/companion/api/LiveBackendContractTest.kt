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

        api.logout()
        assertNull(store.load())
        try { api.login(email, "wrong passphrase!", "IT"); fail() } catch (e: ApiError) { assertEquals("bad_credentials", e.code) }

        // Recovery: reset by emailed code, then sign in with the new password.
        api.forgot(email)
        api.reset(email, lastCode(email, "password reset"), "another long passphrase")
        api.login(email, "another long passphrase", "IT")
        assertEquals(email, api.me().email)
    }
}
