package app.cs23c02.companion.tutor

import app.cs23c02.companion.stepper.Recordings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProgramSearchTest {
    private val programs = Recordings.parsePrograms(File("src/main/assets/stepper/demos.json").readText())
    private fun first(q: String) = ProgramSearch.search(q, programs).firstOrNull()?.id

    @Test fun findsProgramsFromStudentWords() {
        assertEquals("recursion", first("Write a program to find factorial"))
        assertEquals("even-or-odd", first("check if a number is even or odd"))
        assertEquals("running-total", first("average of marks"))
        assertEquals("function-call", first("area of rectangle"))
    }

    @Test fun partialWordsMatch() = assertEquals("recursion", first("factor"))

    @Test fun emptyQueryListsEverything() = assertEquals(programs.size, ProgramSearch.search("  ", programs).size)

    @Test fun unknownTopicFindsNothing() = assertTrue(ProgramSearch.search("bubble sort", programs).isEmpty())
}
