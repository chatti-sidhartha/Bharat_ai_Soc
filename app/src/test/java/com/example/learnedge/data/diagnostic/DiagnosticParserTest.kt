package com.example.learnedge.data.diagnostic

import com.example.learnedge.data.curriculum.CurriculumParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticParserTest {
    private val projectDir = System.getProperty("projectDir") ?: error("projectDir system property not set by Gradle")
    private val questions by lazy { DiagnosticParser.parse(File(projectDir, "src/main/assets/diagnostic.json").readText()) }
    private val tracks by lazy { CurriculumParser.parse(File(projectDir, "src/main/assets/curriculum.json").readText()) }

    @Test
    fun everySkillOfBothTracks_hasExactlyThreeQuestions() {
        tracks.flatMap { it.skills }.forEach { skill ->
            assertEquals("questions for ${skill.id}", 3, questions.count { it.skillId == skill.id })
        }
        assertEquals(tracks.sumOf { it.skills.size } * 3, questions.size)
    }

    @Test
    fun everyQuestionIsTaggedWithATopicOfItsOwnSkill() {
        val topics = tracks.flatMap { it.graph.topics }.associateBy { it.id }
        questions.forEach { q ->
            val topic = topics[q.topicId]
            assertTrue("${q.id}: unknown topic ${q.topicId}", topic != null)
            assertEquals("${q.id}: topic belongs to another skill", q.skillId, topic!!.skillId)
        }
    }

    @Test
    fun everyQuestionHasFourDistinctOptionsAndAnExplanation() {
        questions.forEach { q ->
            assertEquals(q.id, 4, q.options.size)
            assertEquals(q.id, 4, q.options.toSet().size)
            assertTrue(q.id, q.explanation.isNotBlank())
        }
    }

    @Test
    fun answerPositionsAreVaried() {
        // Guards against a quiz where "always pick the first option" scores well.
        val positions = questions.groupingBy { it.answerIndex }.eachCount()
        assertTrue("answer positions: $positions", positions.size == 4 && positions.values.all { it <= questions.size / 2 })
    }
}
