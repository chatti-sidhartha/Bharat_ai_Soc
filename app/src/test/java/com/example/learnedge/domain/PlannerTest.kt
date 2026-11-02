package com.example.learnedge.domain

import com.example.learnedge.data.curriculum.CurriculumParser
import java.io.File
import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    private val eps = 1e-9
    private val start = LocalDate.of(2026, 11, 3)

    // Worked example from the review document: Java 6 -> 8, SQL 4 -> 7, Spring Boot 2 -> 7, equal weights.
    private val example = Track(
        "java-backend", "Java Backend",
        listOf(Skill("java", "Java", 8, 1.0), Skill("sql", "SQL", 7, 1.0), Skill("spring", "Spring Boot", 7, 1.0)),
        KnowledgeGraph(
            listOf(
                Topic("j1", "Java basics", "java", 60),
                Topic("j2", "Collections", "java", 60),
                Topic("s1", "SQL", "sql", 60),
                Topic("sp1", "Spring Boot", "spring", 90),
            ),
            mapOf("j2" to setOf("j1"), "sp1" to setOf("j2", "s1")),
        ),
    )
    private val exampleRatings = mapOf("java" to 6, "sql" to 4, "spring" to 2)

    @Test
    fun workedExample_gapsAre02_03_05() {
        val gaps = SkillGaps.compute(example, mastery = emptyMap(), selfRatings = exampleRatings)
        assertEquals(0.2, gaps.getValue("java").gap, eps)
        assertEquals(0.3, gaps.getValue("sql").gap, eps)
        assertEquals(0.5, gaps.getValue("spring").gap, eps)
    }

    @Test
    fun workedExample_springBootComesAfterItsPrerequisites() {
        val gaps = SkillGaps.compute(example, emptyMap(), exampleRatings)
        val path = PathPlanner.plan(example, emptyMap(), gaps).map { it.topic.id }
        // s1: 0.3*1.1 = 0.33 beats j1: 0.2*1.2 = 0.24; sp1 (0.5) has to wait for j2 and s1.
        assertEquals(listOf("s1", "j1", "j2", "sp1"), path)
    }

    @Test
    fun priority_isGapTimesDescendantBonus() {
        val gaps = SkillGaps.compute(example, emptyMap(), exampleRatings)
        val p = PathPlanner.plan(example, emptyMap(), gaps).associate { it.topic.id to it.priority }
        assertEquals(0.2 * 1.2, p.getValue("j1"), eps) // j1 unlocks j2 and sp1
        assertEquals(0.2 * 1.1, p.getValue("j2"), eps)
        assertEquals(0.3 * 1.1, p.getValue("s1"), eps)
        assertEquals(0.5 * 1.0, p.getValue("sp1"), eps)
    }

    @Test
    fun currentLevel_usesMeanMasteryWhenAvailable() {
        val level = SkillGaps.currentLevel(example, "java", mapOf("j1" to 0.9, "j2" to 0.5), exampleRatings)
        assertEquals(7.0, level, eps)
        assertEquals(4.0, SkillGaps.currentLevel(example, "sql", emptyMap(), exampleRatings), eps)
        assertEquals(0.0, SkillGaps.gap(Skill("x", "X", 5, 1.0), currentLevel = 9.0), eps)
    }

    @Test
    fun masteredTopicsAreSkipped_andTheirDependentsAreStillPlanned() {
        val gaps = SkillGaps.compute(example, emptyMap(), exampleRatings)
        val path = PathPlanner.plan(example, mapOf("j1" to 0.96, "j2" to 0.99), gaps).map { it.topic.id }
        assertEquals(listOf("s1", "sp1"), path)
    }

    @Test
    fun satisfiedTopicsCountAsDone() {
        val gaps = SkillGaps.compute(example, emptyMap(), exampleRatings)
        val path = PathPlanner.plan(example, emptyMap(), gaps, satisfied = setOf("s1")).map { it.topic.id }
        assertEquals(listOf("j1", "j2", "sp1"), path)
    }

    @Test
    fun scheduler_packsGreedilyInPathOrder() {
        val gaps = SkillGaps.compute(example, emptyMap(), exampleRatings)
        val path = PathPlanner.plan(example, emptyMap(), gaps)
        val plan = Scheduler.schedule(path, example.graph, start, days = 10, dailyMinutes = 120)
        assertEquals(listOf(listOf("s1", "j1"), listOf("j2"), listOf("sp1")), plan.days.map { d -> d.items.map { it.topicId } })
        assertEquals(listOf(start, start.plusDays(1), start.plusDays(2)), plan.days.map { it.date })
        assertEquals(270, plan.totalMinutes)
        assertTrue(plan.feasible)
    }

    @Test
    fun scheduler_splitsTopicsLongerThanADay() {
        val gaps = SkillGaps.compute(example, emptyMap(), exampleRatings)
        val path = PathPlanner.plan(example, emptyMap(), gaps)
        val plan = Scheduler.schedule(path, example.graph, start, days = 30, dailyMinutes = 40)
        val spring = plan.days.flatMap { it.items }.filter { it.topicId == "sp1" }
        assertEquals(listOf(40, 40, 10), spring.map { it.minutes })
        assertEquals(listOf(1, 2, 3), spring.map { it.part })
        assertTrue(plan.days.all { it.minutes <= 40 })
    }

    @Test
    fun infeasibleDeadline_defersLowestPriorityLeaves() {
        // a -> b in skill x (big gap), c in skill y (small gap), d in skill z (medium gap). 4 hours of work, 2 days of 60 min.
        val track = Track(
            "t", "T",
            listOf(Skill("x", "X", 10, 1.0), Skill("y", "Y", 10, 1.0), Skill("z", "Z", 10, 1.0)),
            KnowledgeGraph(
                listOf(Topic("a", "A", "x", 60), Topic("b", "B", "x", 60), Topic("c", "C", "y", 60), Topic("d", "D", "z", 60)),
                mapOf("b" to setOf("a")),
            ),
        )
        val gaps = SkillGaps.compute(track, emptyMap(), mapOf("x" to 0, "y" to 9, "z" to 6)) // gaps 1.0, 0.1, 0.4
        val path = PathPlanner.plan(track, emptyMap(), gaps)
        val plan = Scheduler.schedule(path, track.graph, start, days = 2, dailyMinutes = 60)

        assertFalse(plan.feasible)
        assertEquals(listOf("c", "d"), plan.deferred.map { it.topicId })
        assertTrue(plan.deferred.all { it.reason.isNotBlank() })
        assertEquals(listOf("a", "b"), plan.days.flatMap { it.items }.map { it.topicId })
        assertEquals(2, plan.days.size)
        assertEquals(120, plan.totalMinutes)
    }

    @Test
    fun prerequisiteIsNeverDeferredWhileItsDependentIsKept() {
        // "a" has a tiny gap but "b" (big gap) needs it: a must stay, the independent leaf goes instead.
        val track = Track(
            "t", "T",
            listOf(Skill("x", "X", 10, 1.0), Skill("y", "Y", 10, 1.0), Skill("w", "W", 10, 1.0)),
            KnowledgeGraph(
                listOf(Topic("a", "A", "w", 60), Topic("b", "B", "x", 60), Topic("c", "C", "y", 60)),
                mapOf("b" to setOf("a")),
            ),
        )
        val gaps = SkillGaps.compute(track, emptyMap(), mapOf("w" to 10, "x" to 0, "y" to 5))
        val plan = Scheduler.schedule(PathPlanner.plan(track, emptyMap(), gaps), track.graph, start, days = 2, dailyMinutes = 60)
        assertEquals(listOf("c"), plan.deferred.map { it.topicId })
        assertEquals(listOf("a", "b"), plan.days.flatMap { it.items }.map { it.topicId })
    }

    @Test
    fun zeroDays_defersEverything() {
        val gaps = SkillGaps.compute(example, emptyMap(), exampleRatings)
        val plan = Scheduler.schedule(PathPlanner.plan(example, emptyMap(), gaps), example.graph, start, days = 0, dailyMinutes = 60)
        assertTrue(plan.days.isEmpty())
        assertEquals(4, plan.deferred.size)
        assertEquals(0, plan.totalMinutes)
    }

    @Test
    fun randomizedRealCurriculum_neverViolatesPrerequisitesOrDailyBudget() {
        val projectDir = System.getProperty("projectDir") ?: error("projectDir system property not set by Gradle")
        val tracks = CurriculumParser.parse(File(projectDir, "src/main/assets/curriculum.json").readText())
        for (track in tracks) for (seed in 1..60) {
            val rnd = Random(seed)
            val mastery = track.graph.topics.associate { it.id to rnd.nextDouble() }
            val ratings = track.skills.associate { it.id to rnd.nextInt(0, 11) }
            val dailyMinutes = listOf(20, 30, 45, 60, 90, 120, 240).random(rnd)
            val days = rnd.nextInt(0, 60)
            val gaps = SkillGaps.compute(track, mastery, ratings)
            val path = PathPlanner.plan(track, mastery, gaps)
            val plan = Scheduler.schedule(path, track.graph, start, days, dailyMinutes)
            val context = "track=${track.id} seed=$seed H=$dailyMinutes D=$days"

            assertTrue("day over budget: $context", plan.days.all { it.minutes <= dailyMinutes })
            assertTrue("more days than allowed: $context", plan.days.size <= days)

            val order = plan.days.flatMap { it.items }.map { it.topicId }
            val pending = path.map { it.topic.id }.toSet()
            assertEquals("scheduled + deferred must equal pending: $context", pending, order.toSet() + plan.deferred.map { it.topicId })
            for (topicId in order.toSet()) {
                for (prereq in track.graph.getPrerequisites(topicId).filter { it in pending }) {
                    assertTrue("$prereq must be kept for $topicId: $context", prereq in order)
                    assertTrue("$prereq after $topicId: $context", order.lastIndexOf(prereq) < order.indexOf(topicId))
                }
            }
            // Split parts are contiguous and add up to the topic's estimate.
            for (topicId in order.toSet()) {
                val first = order.indexOf(topicId)
                val last = order.lastIndexOf(topicId)
                assertTrue("split parts not contiguous: $context", order.subList(first, last + 1).all { it == topicId })
                val minutes = plan.days.flatMap { it.items }.filter { it.topicId == topicId }.sumOf { it.minutes }
                assertEquals(context, track.graph.topic(topicId).estimatedMinutes, minutes)
            }
        }
    }
}
