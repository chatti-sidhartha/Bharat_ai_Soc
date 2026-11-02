package com.example.learnedge.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyPlannerTest {
    private val eps = 1e-9
    private val today = LocalDate.of(2026, 11, 3)
    private val track = Track(
        "t", "T",
        listOf(Skill("java", "Java", 8, 1.0), Skill("sql", "SQL", 7, 1.0)),
        KnowledgeGraph(
            listOf(Topic("j1", "J1", "java", 60), Topic("j2", "J2", "java", 60), Topic("s1", "S1", "sql", 60)),
            mapOf("j2" to setOf("j1")),
        ),
    )
    private val profile = LearnerProfile(
        trackId = "t", interests = "", selfRatings = mapOf("java" to 4, "sql" to 4),
        dailyMinutes = 60, deadline = today.plusDays(10), diagnosticCompleted = true,
    )

    @Test
    fun learnTransition_isOneBktLearningStep() {
        assertEquals(0.3 + 0.7 * 0.15, Bkt.learn(0.3), eps)
        assertEquals(1.0, Bkt.learn(1.0), eps)
    }

    @Test
    fun daysAvailable_runUntilTheDeadline() {
        val plan = StudyPlanner.generate(track, profile.copy(deadline = today.plusDays(2)), emptyMap(), today)
        assertEquals(2, plan.days.size)
        assertEquals(1, plan.deferred.size)
    }

    @Test
    fun partlyUsedFirstDay_pushesWorkToTheNextDay() {
        val plan = StudyPlanner.generate(track, profile, emptyMap(), today, firstDayUsedMinutes = 30)
        // Nothing of 60 min fits in the 30 min left today, so the plan starts tomorrow.
        assertEquals(today.plusDays(1), plan.days.first().date)
        assertTrue(plan.days.all { it.minutes <= 60 })
    }

    @Test
    fun fullyUsedFirstDay_startsTomorrow() {
        val plan = StudyPlanner.generate(track, profile, emptyMap(), today, firstDayUsedMinutes = 60)
        assertEquals(today.plusDays(1), plan.days.first().date)
    }

    @Test
    fun remainingMinutes_shrinkPartlyStudiedTopics() {
        val plan = StudyPlanner.generate(track, profile, emptyMap(), today, remainingMinutes = mapOf("j1" to 20))
        val j1 = plan.days.flatMap { it.items }.single { it.topicId == "j1" }
        assertEquals(20, j1.minutes)
        assertEquals(140, plan.totalMinutes)
    }

    @Test
    fun satisfiedTopics_areLeftOutButUnlockDependents() {
        val plan = StudyPlanner.generate(track, profile, emptyMap(), today, satisfied = setOf("j1"))
        val ids = plan.days.flatMap { it.items }.map { it.topicId }
        assertEquals(setOf("j2", "s1"), ids.toSet())
    }

    @Test
    fun deadlineInThePast_defersEverything() {
        val plan = StudyPlanner.generate(track, profile.copy(deadline = today.minusDays(1)), emptyMap(), today)
        assertTrue(plan.days.isEmpty())
        assertEquals(3, plan.deferred.size)
    }
}
