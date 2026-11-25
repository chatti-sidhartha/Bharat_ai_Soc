package com.example.learnedge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightShiftTest {
    // --- ThermalGovernor: background ---

    private val governor = ThermalGovernor()

    private fun inputs(
        thermal: ThermalStatus = ThermalStatus.NONE,
        temp: Double? = 33.0,
        percent: Int = 80,
        charging: Boolean = true,
    ) = GovernorInputs(thermal, temp, percent, charging)

    @Test
    fun background_runsWhenCoolAndCharging() {
        assertEquals(BackgroundDecision.Run, governor.background(inputs(ThermalStatus.NONE)))
        assertEquals(BackgroundDecision.Run, governor.background(inputs(ThermalStatus.LIGHT)))
    }

    @Test
    fun background_pausesSixtySecondsBetweenItemsAtModerate() {
        val decision = governor.background(inputs(ThermalStatus.MODERATE))
        assertTrue(decision.toString(), decision is BackgroundDecision.Pause)
        decision as BackgroundDecision.Pause
        assertEquals(60_000L, decision.millis)
        assertTrue(decision.reason.contains("MODERATE"))
    }

    @Test
    fun background_stopsAndReschedulesAtSevereOrHigher() {
        listOf(ThermalStatus.SEVERE, ThermalStatus.CRITICAL, ThermalStatus.EMERGENCY, ThermalStatus.SHUTDOWN).forEach {
            val decision = governor.background(inputs(it))
            assertTrue("$it -> $decision", decision is BackgroundDecision.Stop)
        }
    }

    @Test
    fun background_neverRunsWhileNotCharging_whateverTheTemperature() {
        assertEquals(BackgroundDecision.Stop("not charging"), governor.background(inputs(ThermalStatus.NONE, charging = false)))
        assertTrue(governor.background(inputs(ThermalStatus.LIGHT, charging = false)) is BackgroundDecision.Stop)
    }

    @Test
    fun background_stopsWhenTheBatteryIsLow() {
        val decision = governor.background(inputs(percent = 19))
        assertEquals(BackgroundDecision.Stop("battery low (19%)"), decision)
        assertEquals(BackgroundDecision.Run, governor.background(inputs(percent = 20)))
    }

    @Test
    fun background_usesTheBatteryTemperatureEvenWhenTheThermalStatusIsNone() {
        assertEquals(BackgroundDecision.Run, governor.background(inputs(temp = 39.9)))
        assertTrue(governor.background(inputs(temp = 40.0)) is BackgroundDecision.Pause)
        assertTrue(governor.background(inputs(temp = 42.9)) is BackgroundDecision.Pause)
        assertTrue(governor.background(inputs(temp = 43.0)) is BackgroundDecision.Stop)
    }

    @Test
    fun background_withoutABatteryTemperature_followsTheThermalStatusAlone() {
        assertEquals(BackgroundDecision.Run, governor.background(inputs(temp = null)))
        assertTrue(governor.background(inputs(ThermalStatus.MODERATE, temp = null)) is BackgroundDecision.Pause)
    }

    @Test
    fun theMostSevereConditionWins() {
        // Moderate thermal status would pause, but not charging stops.
        assertTrue(governor.background(inputs(ThermalStatus.MODERATE, charging = false)) is BackgroundDecision.Stop)
        // Severe status stops even though the battery temperature alone would only pause.
        assertTrue(governor.background(inputs(ThermalStatus.SEVERE, temp = 41.0)) is BackgroundDecision.Stop)
    }

    @Test
    fun everyThresholdComesFromTheConfig() {
        val strict = ThermalGovernor(
            GovernorConfig(
                pauseAt = ThermalStatus.LIGHT, stopAt = ThermalStatus.MODERATE, pauseBetweenItemsMs = 5_000,
                batteryTempPauseC = 35.0, batteryTempStopC = 37.0, minBatteryPercent = 50, requireCharging = false,
            ),
        )
        assertEquals(BackgroundDecision.Pause(5_000, "thermal status LIGHT"), strict.background(inputs(ThermalStatus.LIGHT)))
        assertTrue(strict.background(inputs(ThermalStatus.MODERATE)) is BackgroundDecision.Stop)
        assertEquals(BackgroundDecision.Run, strict.background(inputs(charging = false, temp = 30.0, percent = 50)))
        assertTrue(strict.background(inputs(percent = 49)) is BackgroundDecision.Stop)
        assertTrue(strict.background(inputs(temp = 36.0)) is BackgroundDecision.Pause)
    }

    @Test
    fun thermalStatusMapsFromThePlatformInts() {
        assertEquals(ThermalStatus.NONE, ThermalStatus.fromAndroid(0))
        assertEquals(ThermalStatus.MODERATE, ThermalStatus.fromAndroid(2))
        assertEquals(ThermalStatus.SHUTDOWN, ThermalStatus.fromAndroid(6))
        assertEquals(ThermalStatus.NONE, ThermalStatus.fromAndroid(-1))
        assertTrue(ThermalStatus.SEVERE.atLeast(ThermalStatus.MODERATE))
        assertFalse(ThermalStatus.LIGHT.atLeast(ThermalStatus.MODERATE))
    }

    // --- ThermalGovernor: foreground ---

    @Test
    fun foreground_isNormalWhenCool_andPrefersCachedAndCapsTokensFromModerate() {
        val cool = governor.foreground(inputs(ThermalStatus.LIGHT))
        assertFalse(cool.preferCachedOrBundled)
        assertEquals(governor.config.normalMaxOutputChars, cool.maxOutputChars)
        listOf(ThermalStatus.MODERATE, ThermalStatus.SEVERE, ThermalStatus.CRITICAL).forEach {
            val hot = governor.foreground(inputs(it))
            assertTrue("$it", hot.preferCachedOrBundled)
            assertEquals(governor.config.foregroundMaxOutputChars, hot.maxOutputChars)
        }
        assertTrue(governor.config.foregroundMaxOutputChars < governor.config.normalMaxOutputChars)
    }

    @Test
    fun foreground_doesNotDependOnCharging() {
        assertFalse(governor.foreground(inputs(ThermalStatus.NONE, charging = false)).preferCachedOrBundled)
    }

    // --- PreparationPlanner ---

    private val today = 100L
    private val bands = mapOf("a" to LevelBand.BEGINNER, "b" to LevelBand.INTERMEDIATE, "c" to LevelBand.REVISION, "d" to LevelBand.BEGINNER)
    private fun band(topic: String) = bands.getValue(topic)

    private val days = listOf(
        PlanDay(100, listOf("a", "b")),
        PlanDay(101, listOf("c")),
        PlanDay(102, listOf("d")),
    )

    @Test
    fun planner_listsExplanationsOfTheNextTwoPlanDaysInPlanOrder_thenTomorrowsTips() {
        val plan = PreparationPlanner.plan(days, today, ::band, { _, _ -> false }, { false })
        assertEquals(
            listOf(
                PrepItem.Explanation("a", LevelBand.BEGINNER),
                PrepItem.Explanation("b", LevelBand.INTERMEDIATE),
                PrepItem.Explanation("c", LevelBand.REVISION),
                PrepItem.Tips(101),
            ),
            plan,
        )
    }

    @Test
    fun planner_skipsTopicsThatAlreadyHaveAValidCachedExplanationForTheCurrentBand() {
        val plan = PreparationPlanner.plan(days, today, ::band, { topic, band -> topic == "a" && band == LevelBand.BEGINNER }, { false })
        assertEquals(listOf("b", "c"), plan.filterIsInstance<PrepItem.Explanation>().map { it.topicId })
    }

    @Test
    fun planner_aCachedExplanationForAnotherBandOrPromptVersionDoesNotCount() {
        // The cache check is told the band: an explanation cached for a different band answers false.
        val cachedForOldBand = setOf("a" to LevelBand.INTERMEDIATE)
        val plan = PreparationPlanner.plan(days, today, ::band, { topic, band -> (topic to band) in cachedForOldBand }, { false })
        assertTrue(plan.contains(PrepItem.Explanation("a", LevelBand.BEGINNER)))
    }

    @Test
    fun planner_skipsTomorrowsTipsIfTheyAreAlreadyStored_orTomorrowHasNoStudy() {
        assertTrue(PreparationPlanner.plan(days, today, ::band, { _, _ -> true }, { it == 101L }).isEmpty())
        val gap = listOf(PlanDay(100, listOf("a")), PlanDay(103, listOf("b")))
        val plan = PreparationPlanner.plan(gap, today, ::band, { _, _ -> true }, { false })
        assertTrue("day 101 has no study blocks, so there are no tips to prepare", plan.none { it is PrepItem.Tips })
    }

    @Test
    fun planner_theHorizonIsTheNextTwoDaysWithStudy_notTwoCalendarDays() {
        val sparse = listOf(PlanDay(100, listOf("a")), PlanDay(104, listOf("b")), PlanDay(105, listOf("c")))
        val topics = PreparationPlanner.plan(sparse, today, ::band, { _, _ -> false }, { true }).filterIsInstance<PrepItem.Explanation>().map { it.topicId }
        assertEquals(listOf("a", "b"), topics)
    }

    @Test
    fun planner_aTopicInTwoDaysIsPreparedOnce_pastDaysAreIgnored_andAnEmptyPlanNeedsNothing() {
        val split = listOf(PlanDay(99, listOf("d")), PlanDay(100, listOf("a")), PlanDay(101, listOf("a", "b")))
        val topics = PreparationPlanner.plan(split, today, ::band, { _, _ -> false }, { true }).filterIsInstance<PrepItem.Explanation>().map { it.topicId }
        assertEquals(listOf("a", "b"), topics)
        assertTrue(PreparationPlanner.plan(emptyList(), today, ::band, { _, _ -> false }, { false }).isEmpty())
        assertEquals(listOf("a", "b", "c"), PreparationPlanner.horizonTopics(days, today))
    }
}
