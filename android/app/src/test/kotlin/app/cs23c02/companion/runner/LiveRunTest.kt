package app.cs23c02.companion.runner

import app.cs23c02.companion.api.ApiClient
import app.cs23c02.companion.auth.InMemoryTokenStore
import app.cs23c02.companion.ui.AppViewModel
import app.cs23c02.companion.ui.Screen
import app.cs23c02.companion.ui.screens.friendlyError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveRunTest {
    private val runner = CPythonRunner()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = AppViewModel(ApiClient("https://api.invalid", InMemoryTokenStore()), "test", runner = runner)

    /** viewModelScope work runs on the test dispatcher but the runner blocks on a subprocess; wait for it. */
    private fun AppViewModel.await(cond: (Screen) -> Boolean) {
        val until = System.currentTimeMillis() + 20_000
        while (!cond(state.value.screen) && System.currentTimeMillis() < until) Thread.sleep(20)
    }

    @Test fun ownCodeIsRecordedAndExplained() = runBlocking {
        val r = runner.run("total = 0\nfor i in range(3):\n    total += i\nprint(total)\n")
        assertEquals("ok", r.recording.status)
        assertEquals("3\n", r.recording.stdout)
        assertEquals("Adds i to total (the same as total = total + i).", r.lines.single { it.n == 3 }.explain)
        assertEquals("Repeating with for", r.refs["CON-0010"]!!.title)
    }

    @Test fun stepThroughOwnCodeOpensALiveStepper() {
        val vm = vm()
        vm.openPlayground()
        vm.stepThroughCode("Your own code", "x = 2\ny = x * 5\nprint(y)\n")
        vm.await { it is Screen.Stepper }
        val st = vm.state.value.screen as Screen.Stepper
        assertTrue(st.live)
        assertEquals("10\n", st.program.recording.stdout)
        assertTrue(st.back is Screen.Playground)
    }

    @Test fun inputIsAskedForAndTheProgramContinues() {
        val vm = vm()
        val src = "a = int(input('a? '))\nb = int(input('b? '))\nprint(a + b)\n"
        vm.stepThroughCode("Your own code", src)
        vm.await { it is Screen.Stepper }
        var st = vm.state.value.screen as Screen.Stepper
        assertEquals("needs_input", st.program.recording.status)
        val pausedAt = st.program.recording.steps.size

        vm.provideInput("4")
        vm.await { (it as? Screen.Stepper)?.program?.stdin == "4" }
        st = vm.state.value.screen as Screen.Stepper
        assertEquals(pausedAt, st.start)               // continues where it paused
        assertEquals("needs_input", st.program.recording.status)

        vm.provideInput("5")
        vm.await { (it as? Screen.Stepper)?.program?.stdin == "4\n5" }
        st = vm.state.value.screen as Screen.Stepper
        assertEquals("ok", st.program.recording.status)
        assertEquals("a? 4\nb? 5\n9\n", st.program.recording.stdout)
        assertEquals(3, runner.runs)
    }

    @Test fun syntaxErrorsComeBackAsResults() = runBlocking {
        val r = runner.run("if x > 1\n    print(x)\n")
        assertEquals("syntax_error", r.recording.status)
        assertTrue(friendlyError(r.recording.error!!.type, r.recording.error!!.message).startsWith("Add a colon"))
    }

    @Test fun friendlyMessages() {
        assertTrue(friendlyError("NameError", "name 'totl' is not defined").startsWith("Python doesn't know this name"))
        assertTrue(friendlyError("TypeError", "can only concatenate str (not \"int\") to str").contains("int()"))
        assertTrue(friendlyError("IndentationError", "expected an indented block").startsWith("The spaces"))
        assertEquals("KeyError: 'x'", friendlyError("KeyError", "'x'"))
    }
}
