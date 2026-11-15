package com.example.learnedge.data.content

import com.example.learnedge.data.curriculum.CurriculumParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QuestionBankTest {
    private val projectDir = System.getProperty("projectDir") ?: error("projectDir system property not set by Gradle")
    private fun asset(name: String) = File(projectDir, "src/main/assets/$name").readText()

    private val tracks by lazy { CurriculumParser.parse(asset("curriculum.json")) }
    private val topics by lazy { tracks.flatMap { it.graph.topics } }
    private val questions by lazy { QuestionBankParser.parse(asset("question_bank.json")) }

    @Test
    fun everyTopicOfBothTracksHasAtLeastThreeQuestions_andNoQuestionIsAboutAnUnknownTopic() {
        val byTopic = questions.groupBy { it.topicId }
        topics.forEach { assertTrue("questions for ${it.id}", (byTopic[it.id]?.size ?: 0) >= 3) }
        assertEquals(emptySet<String>(), byTopic.keys - topics.map { it.id }.toSet())
        assertEquals(64, topics.size)
        assertEquals(192, questions.size)
    }

    @Test
    fun questionIdsAreUnique() {
        assertEquals(questions.size, questions.map { it.id }.toSet().size)
    }

    @Test
    fun everyQuestionHasFourDistinctOptions_aValidAnswerIndex_andAOneLineExplanation() {
        questions.forEach { q ->
            assertEquals("options of ${q.id}", 4, q.options.size)
            assertEquals("distinct options of ${q.id}", 4, q.options.toSet().size)
            assertTrue("answerIndex of ${q.id}", q.answerIndex in 0..3)
            assertTrue("difficulty of ${q.id}", q.difficulty in 1..3)
            assertTrue("explanation of ${q.id}", q.explanation.isNotBlank() && '\n' !in q.explanation)
        }
    }

    @Test
    fun everyTopicHasThreeToFiveKeyPoints() {
        topics.forEach { t ->
            assertTrue("key points of ${t.id}: ${t.keyPoints.size}", t.keyPoints.size in 3..5)
            assertTrue("blank key point in ${t.id}", t.keyPoints.all { it.isNotBlank() })
        }
    }

    @Test
    fun correctAnswersAreSpreadAcrossAllPositions() {
        val counts = questions.groupingBy { it.answerIndex }.eachCount()
        (0..3).forEach { index ->
            val share = (counts[index] ?: 0).toDouble() / questions.size
            assertTrue("answer position $index is correct only ${"%.0f".format(share * 100)}% of the time", share >= 0.15)
        }
    }

    /**
     * Difficulty was assigned by a text heuristic (code or "what happens" style questions = 3,
     * plain recall = 1, the rest = 2), not by testing students, so only the spread is checked.
     */
    @Test
    fun allThreeDifficultyLevelsAreUsed() {
        val counts = questions.groupingBy { it.difficulty }.eachCount()
        (1..3).forEach { assertTrue("difficulty $it is used for ${counts[it] ?: 0} questions", (counts[it] ?: 0) >= questions.size / 10) }
    }

    /**
     * KNOWN LIMITATION: the correct option is strictly the longest in about 46% of questions
     * (measured 15 Nov 2026: 89 of 192), against roughly 25% expected by chance, so a test-wise
     * guesser gains an edge. This is a ratchet: it fails if the bank gets MORE biased. The target
     * is <= 35%; lower the limit as questions are rebalanced.
     */
    @Test
    fun correctOptionIsStrictlyTheLongest_noMoreOftenThanTheCurrentRatchet() {
        val longest = questions.count { q ->
            q.options[q.answerIndex].length > q.options.filterIndexed { i, _ -> i != q.answerIndex }.maxOf { it.length }
        }
        assertTrue(
            "correct option is strictly the longest in $longest of ${questions.size} questions",
            longest.toDouble() / questions.size <= 0.48,
        )
    }

    @Test
    fun malformedBankFailsLoudly() {
        try {
            QuestionBankParser.parse("""{"questions":[{"id":"q","topicId":"a","question":"?","options":["1","2"],"answerIndex":5,"explanation":"e","difficulty":1}]}""")
            fail("answerIndex outside the options must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("answerIndex"))
        }
    }
}
