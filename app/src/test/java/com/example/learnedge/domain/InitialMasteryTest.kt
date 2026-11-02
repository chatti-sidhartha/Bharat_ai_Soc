package com.example.learnedge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialMasteryTest {
    private val eps = 1e-9
    private val track = Track(
        "t", "T",
        listOf(Skill("java", "Java", 8, 1.0), Skill("sql", "SQL", 7, 1.0)),
        KnowledgeGraph(
            listOf(
                Topic("j1", "J1", "java", 30),
                Topic("j2", "J2", "java", 30),
                Topic("s1", "S1", "sql", 30),
            ),
            mapOf("j2" to setOf("j1")),
        ),
    )

    private fun answer(skill: String, topic: String, correct: Boolean) = DiagnosticAnswer("q-$topic", skill, topic, correct)

    @Test
    fun prior_isHalfTheSelfRatingFraction_cappedAtHalf() {
        assertEquals(0.0, InitialMastery.prior(0), eps)
        assertEquals(0.3, InitialMastery.prior(6), eps)
        assertEquals(0.5, InitialMastery.prior(10), eps)
        assertEquals(0.5, InitialMastery.prior(15), eps)
        assertEquals(0.0, InitialMastery.prior(-3), eps)
    }

    @Test
    fun withoutAnswers_masteryEqualsPrior() {
        val result = InitialMastery.compute(track, mapOf("java" to 6, "sql" to 4), emptyList())
        assertEquals(0.3, result.getValue("j1").mastery, eps)
        assertEquals(0.2, result.getValue("s1").mastery, eps)
    }

    @Test
    fun everyTopicOfASkill_getsAllAnswersOnThatSkill_inOrder() {
        val answers = listOf(answer("java", "j1", true), answer("java", "j2", false), answer("sql", "s1", true))
        val result = InitialMastery.compute(track, mapOf("java" to 6, "sql" to 4), answers)

        val expectedJava = Bkt.update(Bkt.update(0.3, true), false)
        assertEquals(expectedJava, result.getValue("j1").mastery, eps)
        assertEquals(expectedJava, result.getValue("j2").mastery, eps)
        assertEquals(Bkt.update(0.2, true), result.getValue("s1").mastery, eps)
    }

    @Test
    fun attemptsCountOnlyQuestionsTaggedWithTheTopic() {
        val answers = listOf(answer("java", "j1", true), answer("java", "j1", false), answer("java", "j2", true))
        val result = InitialMastery.compute(track, mapOf("java" to 5, "sql" to 5), answers)
        assertEquals(2, result.getValue("j1").attempts)
        assertEquals(1, result.getValue("j1").correct)
        assertEquals(1, result.getValue("j2").attempts)
        assertEquals(0, result.getValue("s1").attempts)
    }

    @Test
    fun allCorrect_raisesMasteryAboveSelfRating_allWrong_lowersIt() {
        val right = List(3) { answer("java", "j1", true) }
        val wrong = List(3) { answer("java", "j1", false) }
        val ratings = mapOf("java" to 6, "sql" to 4)
        assertTrue(InitialMastery.compute(track, ratings, right).getValue("j1").mastery > 0.9)
        assertTrue(InitialMastery.compute(track, ratings, wrong).getValue("j1").mastery < 0.3)
    }
}
