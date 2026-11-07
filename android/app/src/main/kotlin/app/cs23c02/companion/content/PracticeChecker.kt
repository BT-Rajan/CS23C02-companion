package app.cs23c02.companion.content

import app.cs23c02.companion.api.PracticeVerdict
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Answers for the debug SAMPLE questions only (assets/sample-practice-keys.json); real questions are checked on the server. */
@Serializable
data class SampleKey(val answer: String, val explanation: String,
                     @SerialName("option_explanations") val optionExplanations: Map<String, String> = emptyMap())

@Serializable
data class SampleExerciseKey(@SerialName("hidden_tests") val hiddenTests: List<app.cs23c02.companion.content.PublicTest> = emptyList(),
                             val hints: List<String> = emptyList(), @SerialName("solution_approach") val solutionApproach: String = "",
                             @SerialName("reference_solution") val referenceSolution: String = "")

@Serializable
data class SampleKeys(val items: Map<String, SampleKey>, val exercises: Map<String, SampleExerciseKey> = emptyMap(),
                      @SerialName("assessment_items") val assessmentItems: Map<String, SampleKey> = emptyMap())

/**
 * Debug-only stand-in for POST /v1/practice/items/{id}/answer, with the SAME policy as backend/app/practice.py
 * (reveal after 2 wrong attempts or a right one), so the screens behave identically. Never used for book content.
 */
class SamplePracticeChecker(private val keys: Map<String, SampleKey>, val exercises: Map<String, SampleExerciseKey> = emptyMap(),
                            val assessmentKeys: Map<String, SampleKey> = emptyMap()) {
    private val wrong = HashMap<String, Int>()

    fun check(itemId: String, option: String): PracticeVerdict {
        val k = keys[itemId] ?: throw ContentError("This question isn't available for practice.")
        val correct = option == k.answer
        if (!correct) wrong[itemId] = (wrong[itemId] ?: 0) + 1
        val n = wrong[itemId] ?: 0
        val reveal = correct || n >= REVEAL_AFTER
        return PracticeVerdict(itemId, correct, n, if (correct) null else k.optionExplanations[option], reveal,
            if (reveal) k.answer else null, if (reveal) k.explanation else null)
    }

    companion object {
        const val REVEAL_AFTER = 2
        fun parse(text: String) = ContentJson.decodeFromString(SampleKeys.serializer(), text)
            .let { SamplePracticeChecker(it.items, it.exercises, it.assessmentItems) }
    }
}
