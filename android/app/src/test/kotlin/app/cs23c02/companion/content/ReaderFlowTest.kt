package app.cs23c02.companion.content

import app.cs23c02.companion.api.ApiClient
import app.cs23c02.companion.auth.InMemoryTokenStore
import app.cs23c02.companion.ui.AppViewModel
import app.cs23c02.companion.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Journeys J2/J3 on the real signed SAMPLE package, through the ViewModel, with no server. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderFlowTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val m = PackageVerifier.verifyManifest(Sample.manifest, Sample.signature, Sample.KEY, "0.0.1")
    private fun vm(): AppViewModel {
        val api = ApiClient("https://api.invalid", InMemoryTokenStore())
        return AppViewModel(api, "test", previewTutor = true, readerFor = { _ ->
            ReaderRepository(api, ContentStore(File(tmp.root, "pkg"), Sample.KEY, "0.0.1"), ProgressStore(File(tmp.root, "p.json"))) {
                (Sample.signature + "\n").toByteArray() + Sample.manifest to m.files.associate { it.path to Sample.file(it.path) }
            }
        })
    }
    private fun AppViewModel.awaitPkg() {
        val until = System.currentTimeMillis() + 10_000
        while (reader.value.pkg == null && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    @Test fun sampleReaderStartsAtTheFirstLesson() {
        val vm = vm()
        vm.openSampleReader()
        vm.awaitPkg()
        assertTrue((vm.state.value.screen as Screen.Home).sample)
        assertEquals("LSN-9001", vm.reader.value.resume)
        assertTrue(vm.reader.value.sample)
    }

    @Test fun readDoneNextAndContinue() {
        val vm = vm()
        vm.openSampleReader(); vm.awaitPkg()
        vm.openLesson("LSN-9001")
        val page = vm.state.value.screen as Screen.LessonPage
        assertEquals("UNT-9001", (page.back as Screen.UnitPage).unitId)   // back goes to the lesson's unit
        vm.saveLessonPosition("LSN-9001", 3)
        vm.completeLesson("LSN-9001")
        assertEquals("LSN-9002", (vm.state.value.screen as Screen.LessonPage).lessonId) // straight on to the next lesson
        assertEquals(setOf("LSN-9001"), vm.reader.value.completed)
        vm.back(); vm.back()
        assertTrue(vm.state.value.screen is Screen.Home)
        assertEquals("LSN-9002", vm.reader.value.resume)                    // Continue = the open lesson

        // A fresh app start (new ViewModel, same storage) remembers everything, offline.
        val again = vm(); again.openSampleReader(); again.awaitPkg()
        assertEquals("LSN-9002", again.reader.value.resume)
        assertEquals(3, again.reader.value.positions["LSN-9001"])
    }

    @Test fun finishingTheLastLessonReturnsHomeWithAMessage() {
        val vm = vm()
        vm.openSampleReader(); vm.awaitPkg()
        m.nav.lessonIds().forEach { vm.openLesson(it); vm.completeLesson(it) }
        assertTrue(vm.state.value.screen is Screen.Home)
        assertNotNull(vm.state.value.info)
        assertEquals(null, vm.reader.value.resume)
    }

    @Test fun unitAndLeave() {
        val vm = vm()
        vm.openSampleReader(); vm.awaitPkg()
        vm.openUnit("UNT-9002")
        assertEquals("UNT-9002", (vm.state.value.screen as Screen.UnitPage).unitId)
        vm.back(); vm.leaveSample()
        assertEquals(Screen.Welcome, vm.state.value.screen)
        assertEquals(null, vm.reader.value.pkg)
    }
}
