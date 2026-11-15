package com.example.learnedge.data.content

import com.example.learnedge.data.curriculum.CurriculumParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ContentParserTest {
    private val projectDir = System.getProperty("projectDir") ?: error("projectDir system property not set by Gradle")
    private fun asset(name: String) = File(projectDir, "src/main/assets/$name").readText()

    private val tracks by lazy { CurriculumParser.parse(asset("curriculum.json")) }
    private val content by lazy { ContentRepository.ASSETS.map { ContentParser.parse(asset(it)) } }
    private val allTopics by lazy { content.flatMap { it.topics } }

    @Test
    fun everyTopicOfBothTracks_hasContent_andNothingExtra() {
        tracks.forEach { track ->
            val expected = track.graph.topics.map { it.id }.toSet()
            val actual = content.single { it.trackId == track.id }.topics.map { it.topicId }
            assertEquals("duplicate topics for ${track.id}", actual.size, actual.toSet().size)
            assertEquals("content topics for ${track.id}", expected, actual.toSet())
        }
        assertEquals(64, allTopics.size)
    }

    @Test
    fun everyTopicHasThreeKeyPointsAndThreeFourOptionQuestions() {
        allTopics.forEach { t ->
            assertEquals("key points of ${t.topicId}", 3, t.keyPoints.size)
            assertEquals("questions of ${t.topicId}", 3, t.questions.size)
            t.questions.forEach { q ->
                assertEquals("options of ${q.id}", 4, q.options.size)
                assertTrue("explanation of ${q.id}", q.explanation.isNotBlank())
            }
        }
    }

    @Test
    fun questionIdsAreGloballyUnique_andFollowTheTopicId() {
        val questions = allTopics.flatMap { it.questions }
        assertEquals(questions.size, questions.map { it.id }.toSet().size)
        allTopics.forEach { t ->
            t.questions.forEachIndexed { i, q -> assertEquals("b-${t.topicId}-${i + 1}", q.id) }
        }
    }

    @Test
    fun correctAnswersAreSpreadAcrossAllPositions() {
        val counts = allTopics.flatMap { it.questions }.groupingBy { it.answerIndex }.eachCount()
        val total = counts.values.sum()
        (0..3).forEach { index ->
            val share = (counts[index] ?: 0).toDouble() / total
            assertTrue("answer position $index is correct only ${"%.0f".format(share * 100)}% of the time", share >= 0.15)
        }
    }

    /**
     * KNOWN LIMITATION: the correct option is strictly the longest in about 46% of questions
     * (measured 15 Nov 2026: 89 of 192), against roughly 25% expected by chance, so a test-wise
     * guesser gains an edge. This is a ratchet: it fails if the bank gets MORE biased. The target
     * is <= 35%; lower the limit as questions are rebalanced.
     */
    @Test
    fun correctOptionIsStrictlyTheLongest_noMoreOftenThanTheCurrentRatchet() {
        val questions = allTopics.flatMap { it.questions }
        val longest = questions.count { q ->
            q.options[q.answerIndex].length > q.options.filterIndexed { i, _ -> i != q.answerIndex }.maxOf { it.length }
        }
        assertTrue(
            "correct option is strictly the longest in $longest of ${questions.size} questions",
            longest.toDouble() / questions.size <= 0.48,
        )
    }

    @Test
    fun malformedContentFailsLoudly() {
        try {
            ContentParser.parse("""{"track":"x","topics":[{"topicId":"a","keyPoints":["k"],"questions":[{"id":"q","question":"?","options":["1","2"],"answerIndex":5}]}]}""")
            fail("answerIndex outside the options must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("answerIndex"))
        }
    }
}
