package com.example.learnedge.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTest {
    private val eps = 1e-9

    // --- Overall progress to the goal ---

    private fun gap(id: String, target: Int, weight: Double, level: Double) =
        SkillGap(Skill(id, id, target, weight), level, SkillGaps.gap(Skill(id, id, target, weight), level))

    @Test
    fun overallProgress_isTheWeightedShareOfTheTargetsReached() {
        // (4 + 7) / (8 + 7)
        assertEquals(11.0 / 15.0, GoalProgress.overall(listOf(gap("java", 8, 1.0, 4.0), gap("sql", 7, 1.0, 7.0))), eps)
    }

    @Test
    fun overallProgress_capsEachSkillAtItsTarget_andWeightsSkills() {
        // java is above target (counts as 8 of 8); sql is at 3.5 of 7 with weight 2: (8 + 2 * 3.5) / (8 + 2 * 7)
        val value = GoalProgress.overall(listOf(gap("java", 8, 1.0, 9.5), gap("sql", 7, 2.0, 3.5)))
        assertEquals(15.0 / 22.0, value, eps)
    }

    @Test
    fun overallProgress_isCompleteWhenThereIsNothingToReach() {
        assertEquals(1.0, GoalProgress.overall(emptyList()), eps)
        assertEquals(1.0, GoalProgress.overall(listOf(gap("java", 8, 1.0, 8.0))), eps)
    }

    // --- Minutes done vs planned, last 7 days ---

    private val today = LocalDate.of(2026, 11, 15)

    @Test
    fun lastDays_returnsSevenDaysOldestFirst_endingToday() {
        val days = StudyLog.lastDays(today, 7, emptyList())
        assertEquals(7, days.size)
        assertEquals(LocalDate.of(2026, 11, 9), days.first().date)
        assertEquals(today, days.last().date)
        assertTrue(days.all { it.doneMinutes == 0 && it.plannedMinutes == 0 })
    }

    @Test
    fun lastDays_countsDoneMinutesOnTheDayDone_andPlannedMinutesOnTheDayPlanned() {
        val items = listOf(
            LoggedItem(plannedDay = today.minusDays(1), doneDay = today.minusDays(1), minutes = 30),
            LoggedItem(plannedDay = today, doneDay = today, minutes = 45),
            LoggedItem(plannedDay = today, doneDay = null, minutes = 60),
            // Planned for an earlier day but done today: planned there, done here.
            LoggedItem(plannedDay = today.minusDays(3), doneDay = today, minutes = 20),
            // Tomorrow is outside the window; so is a day 7 days ago.
            LoggedItem(plannedDay = today.plusDays(1), doneDay = null, minutes = 90),
            LoggedItem(plannedDay = today.minusDays(7), doneDay = today.minusDays(7), minutes = 50),
        )
        val days = StudyLog.lastDays(today, 7, items).associateBy { it.date }
        assertEquals(30, days.getValue(today.minusDays(1)).doneMinutes)
        assertEquals(30, days.getValue(today.minusDays(1)).plannedMinutes)
        assertEquals(65, days.getValue(today).doneMinutes)
        assertEquals(105, days.getValue(today).plannedMinutes)
        assertEquals(0, days.getValue(today.minusDays(3)).doneMinutes)
        assertEquals(20, days.getValue(today.minusDays(3)).plannedMinutes)
        assertFalse(days.containsKey(today.minusDays(7)))
    }

    // --- Mastery over time ---

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    private fun attempt(question: String, topic: String, time: String, before: Double, after: Double) =
        QuizAttempt(question, topic, correct = after > before, timestamp = at(time), pLBefore = before, pLAfter = after)

    private val attempts = listOf(
        attempt("a1", "A", "2026-11-15T10:00:00Z", 0.26, 0.67),
        attempt("a2", "A", "2026-11-15T10:01:00Z", 0.67, 0.92),
        attempt("b1", "B", "2026-11-16T09:00:00Z", 0.30, 0.20),
    )

    @Test
    fun timeline_startsWithABaseline_thenOnePointPerDay_averagingAllQuizzedTopics() {
        val points = MasteryTimeline.build(attempts, emptySet(), ZoneOffset.UTC)
        assertEquals(3, points.size)
        assertTrue(points[0].isBaseline)
        assertEquals(LocalDate.of(2026, 11, 15), points[0].date)
        assertEquals((0.26 + 0.30) / 2, points[0].averageMastery, eps)
        // Day 1: A is at 0.92, B still at its baseline 0.30.
        assertEquals(LocalDate.of(2026, 11, 15), points[1].date)
        assertEquals((0.92 + 0.30) / 2, points[1].averageMastery, eps)
        // Day 2: B dropped to 0.20.
        assertEquals(LocalDate.of(2026, 11, 16), points[2].date)
        assertEquals((0.92 + 0.20) / 2, points[2].averageMastery, eps)
        assertTrue(points.all { it.topicsQuizzed == 2 })
    }

    @Test
    fun timeline_ignoresReportedQuestions() {
        val points = MasteryTimeline.build(attempts, setOf("b1"), ZoneOffset.UTC)
        assertEquals(2, points.size)
        assertEquals(1, points.last().topicsQuizzed)
        assertEquals(0.92, points.last().averageMastery, eps)
    }

    @Test
    fun timeline_isEmptyWithoutAttempts_andOrdersByTime() {
        assertTrue(MasteryTimeline.build(emptyList(), emptySet(), ZoneOffset.UTC).isEmpty())
        val shuffled = MasteryTimeline.build(attempts.reversed(), emptySet(), ZoneOffset.UTC)
        assertEquals(MasteryTimeline.build(attempts, emptySet(), ZoneOffset.UTC), shuffled)
    }
}
