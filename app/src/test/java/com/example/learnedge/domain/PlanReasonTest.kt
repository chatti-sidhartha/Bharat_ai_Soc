package com.example.learnedge.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanReasonTest {
    private val eps = 1e-9
    private val today = LocalDate.of(2026, 11, 16)

    private val track = Track(
        "t", "T",
        listOf(Skill("java", "Java", 8, 1.0), Skill("sql", "SQL", 7, 1.0)),
        KnowledgeGraph(
            listOf(Topic("j1", "J1", "java", 60), Topic("j2", "J2", "java", 60), Topic("s1", "S1", "sql", 60)),
            mapOf("j2" to setOf("j1")),
        ),
    )
    private val selfRatings = mapOf("java" to 4, "sql" to 4)

    // Java level 2.0 (target 8): gap 0.60. SQL level 5.0 (target 7): gap 0.20.
    private val mastery = mapOf("j1" to 0.2, "j2" to 0.2, "s1" to 0.5)
    private val reasons by lazy { PlanReasons.compute(track, mastery, selfRatings) }

    // --- The numbers come from the planner's own functions ---

    @Test
    fun reasonsUseTheSameGapPriorityAndDependentCountAsThePlanner() {
        val gaps = SkillGaps.compute(track, mastery, selfRatings)
        track.graph.topics.forEach { topic ->
            val reason = reasons.getValue(topic.id)
            assertEquals(PathPlanner.priority(track, topic, gaps), reason.priority, eps)
            assertEquals(track.graph.descendantCount(topic.id), reason.dependents)
            assertEquals(gaps.getValue(topic.skillId).gap, reason.skillGap, eps)
        }
        assertEquals(0.66, reasons.getValue("j1").priority, eps)
        assertEquals(0.60, reasons.getValue("j2").priority, eps)
        assertEquals(1, reasons.getValue("j1").gapRank)
        assertEquals(2, reasons.getValue("s1").gapRank)
    }

    @Test
    fun exposingReasonsDoesNotChangeThePlan() {
        val profile = LearnerProfile(
            trackId = "t", interests = "", selfRatings = selfRatings,
            dailyMinutes = 60, deadline = today.plusDays(5), diagnosticCompleted = true,
        )
        val before = StudyPlanner.generate(track, profile, mastery, today)
        PlanReasons.compute(track, mastery, selfRatings)
        assertEquals(before, StudyPlanner.generate(track, profile, mastery, today))
    }

    // --- Templates, end to end ---

    @Test
    fun scheduledTopicWithNoPrerequisites() {
        assertEquals(
            "Scheduled today because Java is your largest gap (0.60). It unlocks 1 later topic, which gives it " +
                "priority 0.66 = 0.60 × (1 + 0.1 × 1). It has no prerequisites.",
            ReasonText.render(reasons.getValue("j1"), "today"),
        )
    }

    @Test
    fun scheduledTopicWithAnUnmasteredPrerequisite_onALaterDay() {
        assertEquals(
            "Scheduled on Tue 17 Nov because Java is your largest gap (0.60). No later topic depends on it, so its " +
                "priority is just the gap (0.60). Its prerequisite is not mastered yet, so that topic is planned before this one.",
            ReasonText.render(reasons.getValue("j2"), "on Tue 17 Nov"),
        )
    }

    @Test
    fun aSmallerGapIsRankedAndWorded() {
        assertEquals(
            "Scheduled today because SQL is your second-largest gap (0.20). No later topic depends on it, so its " +
                "priority is just the gap (0.20). It has no prerequisites.",
            ReasonText.render(reasons.getValue("s1"), "today"),
        )
    }

    @Test
    fun theExampleFromTheSpecification() {
        val reason = reason(skill = "Spring Boot", gap = 0.50, rank = 1, dependents = 4, prerequisites = 2, mastered = 2)
        assertEquals(
            "Scheduled today because Spring Boot is your largest gap (0.50). It unlocks 4 later topics, which gives it " +
                "priority 0.70 = 0.50 × (1 + 0.1 × 4). You have mastered both prerequisites.",
            ReasonText.render(reason, "today"),
        )
    }

    // --- Template branches ---

    private fun reason(
        skill: String = "Skill",
        gap: Double = 0.30,
        rank: Int = 1,
        dependents: Int = 0,
        prerequisites: Int = 0,
        mastered: Int = 0,
    ) = PlanReason("x", "Topic X", skill, gap, rank, gap * (1 + 0.1 * dependents), dependents, prerequisites, mastered)

    private fun sentences(text: String) = text.split(Regex("(?<=\\.)\\s+"))

    @Test
    fun gapRanksAreWorded() {
        assertTrue(ReasonText.render(reason(rank = 1), "today").contains("is your largest gap"))
        assertTrue(ReasonText.render(reason(rank = 2), "today").contains("is your second-largest gap"))
        assertTrue(ReasonText.render(reason(rank = 3), "today").contains("is your third-largest gap"))
        assertTrue(ReasonText.render(reason(rank = 4), "today").contains("is one of your gaps"))
    }

    @Test
    fun dependentCountIsWordedForZeroOneAndMany() {
        assertTrue(ReasonText.render(reason(dependents = 0), "today").contains("No later topic depends on it"))
        assertTrue(ReasonText.render(reason(dependents = 1), "today").contains("It unlocks 1 later topic,"))
        assertTrue(ReasonText.render(reason(dependents = 7), "today").contains("It unlocks 7 later topics,"))
    }

    @Test
    fun prerequisiteStatusIsWordedForEveryCase() {
        fun text(prerequisites: Int, mastered: Int) = ReasonText.render(reason(prerequisites = prerequisites, mastered = mastered), "today")
        assertTrue(text(0, 0).endsWith("It has no prerequisites."))
        assertTrue(text(1, 1).endsWith("You have mastered its prerequisite."))
        assertTrue(text(2, 2).endsWith("You have mastered both prerequisites."))
        assertTrue(text(3, 3).endsWith("You have mastered all 3 prerequisites."))
        assertTrue(text(1, 0).endsWith("Its prerequisite is not mastered yet, so that topic is planned before this one."))
        assertTrue(text(3, 0).endsWith("None of its 3 prerequisites is mastered yet, so they are planned before this topic."))
        assertTrue(text(3, 1).endsWith("1 of its 3 prerequisites are mastered; the others are planned before this topic."))
    }

    @Test
    fun aSkillAlreadyAtItsTargetIsExplainedWithoutAGap() {
        val text = ReasonText.render(reason(gap = 0.0, skill = "Core Java"), "today")
        assertTrue(text, text.startsWith("Scheduled today because Topic X is not mastered yet, although Core Java is already at its target level."))
    }

    @Test
    fun aDeferredTopicGetsTheSchedulersReasonAndItsPriority() {
        val text = ReasonText.render(reason(dependents = 2), whenText = "", deferredReason = "Not enough time before the deadline.")
        assertEquals(
            "Deferred: Not enough time before the deadline. It unlocks 2 later topics, which gives it priority 0.36 = 0.30 × " +
                "(1 + 0.1 × 2). Add study time or move the deadline to bring it back into the plan.",
            text,
        )
    }

    @Test
    fun everyReasonIsTwoToThreeSentences_andNeverContainsAnUnfilledPlaceholder() {
        track.graph.topics.forEach { topic ->
            val reason = reasons.getValue(topic.id)
            listOf(ReasonText.render(reason, "today"), ReasonText.render(reason, "", "Does not fit.")).forEach { text ->
                assertTrue(text, sentences(text).size in 2..3)
                assertTrue(text, '$' !in text && '{' !in text && "null" !in text)
            }
        }
    }
}
