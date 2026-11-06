package app.cs23c02.companion.ui

import app.cs23c02.companion.api.ApiClient
import app.cs23c02.companion.auth.InMemoryTokenStore
import app.cs23c02.companion.auth.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs the whole sign-up → verify → activate journey (J1) against a fake server, synchronously. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private val store = InMemoryTokenStore()

    private val tokens = """{"access_token":"A1","refresh_token":"R1","token_type":"Bearer","expires_in":900}"""
    private val meEmpty = """{"email":"s@example.com","entitlements":[]}"""
    private val meActive = """{"email":"s@example.com","entitlements":[{"edition_id":"ed1","title":"CS23C02","activated_at":"2026-11-06T00:00:00Z"}]}"""
    private fun err(status: Int, code: String, msg: String) =
        MockResponse().setResponseCode(status).setBody("""{"error":{"message":"$msg","code":"$code"}}""")

    @Before fun setUp() {
        Dispatchers.setMain(main)
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() { server.shutdown(); Dispatchers.resetMain() }

    private fun vm() = AppViewModel(ApiClient(server.url("/").toString(), store, OkHttpClient(), main), "Test phone")

    @Test fun firstLaunchShowsWelcome() = assertEquals(Screen.Welcome, vm().state.value.screen)

    @Test fun fullFirstTimeJourney() {
        val vm = vm()
        vm.openCreateAccount()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"code_sent"}"""))
        vm.createAccount(" s@example.com ", "a good passphrase")
        assertEquals(Screen.VerifyEmail("s@example.com"), vm.state.value.screen)

        server.enqueue(MockResponse().setBody(tokens))
        server.enqueue(MockResponse().setBody(meEmpty))
        vm.verify("s@example.com", "123456")
        assertEquals(Screen.Activate, vm.state.value.screen)

        server.enqueue(MockResponse().setBody("""{"edition_id":"ed1","title":"CS23C02","activated_at":"2026-11-06T00:00:00Z"}"""))
        server.enqueue(MockResponse().setBody(meActive))
        vm.activate("z83g bhab jd4t h5bb")
        val home = vm.state.value.screen
        assertTrue(home is Screen.Home)
        assertEquals("CS23C02", (home as Screen.Home).me.entitlements.single().title)
        assertEquals(5, server.requestCount)
    }

    @Test fun localChecksDoNotCallTheServer() {
        val vm = vm()
        vm.createAccount("not-an-email", "short")
        assertNotNull(vm.state.value.fieldErrors["email"]); assertNotNull(vm.state.value.fieldErrors["password"])
        store.save(Tokens("A", "R", Long.MAX_VALUE))
        vm.activate("Z83G-BHAB-JD4T-H5BC") // wrong check character
        assertNotNull(vm.state.value.fieldErrors["code"])
        assertEquals(0, server.requestCount)
    }

    @Test fun unverifiedLoginGoesToVerify() {
        val vm = vm()
        server.enqueue(err(403, "email_unverified", "Please verify your email. We have sent you a new code."))
        vm.signIn("s@example.com", "a good passphrase")
        assertEquals(Screen.VerifyEmail("s@example.com"), vm.state.value.screen)
        assertEquals("Please verify your email. We have sent you a new code.", vm.state.value.info)
    }

    @Test fun wrongPasswordShowsServerMessageAndStays() {
        val vm = vm()
        vm.openSignIn()
        server.enqueue(err(401, "bad_credentials", "Email or password is incorrect."))
        vm.signIn("s@example.com", "wrong password")
        assertEquals(Screen.SignIn, vm.state.value.screen)
        assertEquals("Email or password is incorrect.", vm.state.value.error)
        assertEquals(false, vm.state.value.busy)
    }

    @Test fun resetReturnsToSignInWithEmailPrefilled() {
        val vm = vm()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"status":"code_sent"}"""))
        vm.sendResetCode("s@example.com")
        assertEquals(Screen.Reset("s@example.com"), vm.state.value.screen)
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        vm.resetPassword("s@example.com", "123456", "a new passphrase")
        assertEquals(Screen.SignIn, vm.state.value.screen)
        assertEquals("s@example.com", vm.state.value.lastEmail)
    }

    @Test fun returningActivatedStudentGoesStraightHome() {
        store.save(Tokens("A", "R", Long.MAX_VALUE))
        server.enqueue(MockResponse().setBody(meActive))
        assertTrue(vm().state.value.screen is Screen.Home)
    }

    @Test fun returningStudentOfflineSeesRetry() {
        store.save(Tokens("A", "R", Long.MAX_VALUE))
        server.shutdown()
        assertEquals(Screen.Offline, vm().state.value.screen)
    }

    @Test fun alreadyActivatedGoesHome() {
        store.save(Tokens("A", "R", Long.MAX_VALUE))
        server.enqueue(MockResponse().setBody(meEmpty))
        val vm = vm()
        server.enqueue(err(409, "already_activated", "This book is already activated on your account."))
        server.enqueue(MockResponse().setBody(meActive))
        vm.activate("Z83G-BHAB-JD4T-H5BB")
        assertTrue(vm.state.value.screen is Screen.Home)
    }

    @Test fun stepperOpensFromHomeAndBackReturns() {
        store.save(Tokens("A", "R", Long.MAX_VALUE))
        server.enqueue(MockResponse().setBody(meActive))
        val demos = app.cs23c02.companion.stepper.Recordings.parsePrograms(java.io.File("src/main/assets/stepper/demos.json").readText())
        val vm = AppViewModel(ApiClient(server.url("/").toString(), store, OkHttpClient(), main), "Test phone", demos)
        val home = vm.state.value.screen
        vm.openStepper("recursion")
        val st = vm.state.value.screen
        assertTrue(st is Screen.Stepper && st.program.id == "recursion")
        assertTrue(vm.back())
        assertEquals(home, vm.state.value.screen)
        vm.openStepper("no-such-demo")
        assertEquals(home, vm.state.value.screen)

        vm.openTutor()
        val tutor = vm.state.value.screen
        assertTrue(tutor is Screen.Tutor)
        vm.openTypeIt("even-or-odd")
        assertTrue((vm.state.value.screen as Screen.TypeIt).program.id == "even-or-odd")
        vm.back(); assertEquals(tutor, vm.state.value.screen)
        vm.back(); assertEquals(home, vm.state.value.screen)
    }

    @Test fun signOutClearsTokens() {
        store.save(Tokens("A", "R", Long.MAX_VALUE))
        server.enqueue(MockResponse().setBody(meActive))
        val vm = vm()
        server.enqueue(MockResponse().setResponseCode(204))
        vm.signOut()
        assertEquals(Screen.Welcome, vm.state.value.screen)
        assertEquals(null, store.load())
    }
}
