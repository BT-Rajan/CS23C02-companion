package app.cs23c02.companion.api

import app.cs23c02.companion.auth.InMemoryTokenStore
import app.cs23c02.companion.auth.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ApiClientTest {
    private lateinit var server: MockWebServer
    private val store = InMemoryTokenStore()
    private var now = 1_000_000L
    private lateinit var api: ApiClient

    private fun tokens(n: Int) = """{"access_token":"A$n","refresh_token":"R$n","token_type":"Bearer","expires_in":900}"""
    private val me = """{"email":"s@example.com","entitlements":[]}"""

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = ApiClient(server.url("/").toString().trimEnd('/'), store, OkHttpClient(), Dispatchers.IO) { now }
    }

    @After fun tearDown() = server.shutdown()

    @Test fun loginStoresTokensAndSendsNoBearer() = runBlocking {
        server.enqueue(MockResponse().setBody(tokens(1)))
        api.login("s@example.com", "pw-1234567890", "Pixel")
        val req = server.takeRequest()
        assertEquals("/v1/auth/login", req.path)
        assertNull(req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("\"device_label\":\"Pixel\""))
        val t = store.load()!!
        assertEquals("A1", t.access); assertEquals("R1", t.refresh)
        assertEquals(now + 870_000, t.accessExpiresAtMs) // refreshed 30 s early
    }

    @Test fun serverErrorShapeIsParsed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "900")
            .setBody("""{"error":{"message":"Too many attempts. Please wait a while and try again.","code":"rate_limited"}}"""))
        try { api.login("s@example.com", "x", "") ; fail() } catch (e: ApiError) {
            assertEquals(429, e.status); assertEquals("rate_limited", e.code); assertEquals(900, e.retryAfterSeconds)
            assertTrue(e.message.startsWith("Too many attempts"))
        }
    }

    @Test fun nonJsonErrorGetsSafeMessage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))
        try { api.forgot("s@example.com"); fail() } catch (e: ApiError) {
            assertEquals(502, e.status); assertTrue(!e.message.contains("html"))
        }
    }

    @Test fun networkFailureIsReportedAsNetwork() = runBlocking {
        server.shutdown()
        try { api.forgot("s@example.com"); fail() } catch (e: ApiError) { assertTrue(e.isNetwork) }
    }

    @Test fun authedCallWithoutSessionIsSignedOut() = runBlocking {
        try { api.me(); fail() } catch (e: ApiError) { assertEquals(401, e.status) }
        assertEquals(0, server.requestCount)
    }

    @Test fun on401RefreshesOnceAndRetries() = runBlocking {
        store.save(Tokens("A0", "R0", now + 60_000))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Please sign in again.","code":"unauthenticated"}}"""))
        server.enqueue(MockResponse().setBody(tokens(1)))
        server.enqueue(MockResponse().setBody(me))
        assertEquals("s@example.com", api.me().email)
        assertEquals("Bearer A0", server.takeRequest().getHeader("Authorization"))
        assertTrue(server.takeRequest().body.readUtf8().contains("\"refresh_token\":\"R0\""))
        assertEquals("Bearer A1", server.takeRequest().getHeader("Authorization"))
        assertEquals("R1", store.load()!!.refresh)
    }

    @Test fun expiredAccessTokenIsRefreshedBeforeTheCall() = runBlocking {
        store.save(Tokens("A0", "R0", now - 1))
        server.enqueue(MockResponse().setBody(tokens(1)))
        server.enqueue(MockResponse().setBody(me))
        api.me()
        assertEquals("/v1/auth/refresh", server.takeRequest().path)
        assertEquals("Bearer A1", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun failedRefreshWipesTokens() = runBlocking {
        store.save(Tokens("A0", "R0", now - 1))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Please sign in again.","code":"unauthenticated"}}"""))
        try { api.me(); fail() } catch (e: ApiError) { assertTrue(e.isUnauthenticated) }
        assertNull(store.load())
    }

    @Test fun parallelCallsShareOneRefresh() = runBlocking {
        store.save(Tokens("A0", "R0", now - 1))
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/auth/refresh" -> { Thread.sleep(100); MockResponse().setBody(tokens(refreshes.incrementAndGet())) }
                else -> MockResponse().setBody(me)
            }
        }
        (1..5).map { async(Dispatchers.IO) { api.me() } }.awaitAll()
        assertEquals(1, refreshes.get()) // a second refresh with R0 would trip the server's reuse detection
    }

    @Test fun logoutClearsLocallyEvenOffline() = runBlocking {
        store.save(Tokens("A0", "R0", now + 60_000))
        server.shutdown()
        api.logout()
        assertNull(store.load())
    }

    @Test fun activateSendsOnlyTheCode() = runBlocking {
        store.save(Tokens("A0", "R0", now + 60_000))
        server.enqueue(MockResponse().setBody("""{"edition_id":"ed1","title":"CS23C02 1st ed.","activated_at":"2026-11-06T00:00:00Z"}"""))
        assertEquals("ed1", api.activate("Z83G-BHAB-JD4T-H5BB").editionId)
        assertEquals("""{"code":"Z83G-BHAB-JD4T-H5BB"}""", server.takeRequest().body.readUtf8())
    }
}
