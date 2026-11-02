package com.example.learnedge.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One call that turns the learner's state into a plan: skill gaps -> priority path -> schedule. */
object StudyPlanner {
    /**
     * @param satisfied topics already studied (or kept elsewhere) that count as done for ordering.
     * @param remainingMinutes minutes still needed for partly studied topics; others use their estimate.
     * @param firstDayUsedMinutes minutes of [startDate] already taken by studied or kept items.
     */
    fun generate(
        track: Track,
        profile: LearnerProfile,
        mastery: Map<String, Double>,
        startDate: LocalDate,
        satisfied: Set<String> = emptySet(),
        remainingMinutes: Map<String, Int> = emptyMap(),
        firstDayUsedMinutes: Int = 0,
    ): StudyPlan {
        val gaps = SkillGaps.compute(track, mastery, profile.selfRatings)
        val path = PathPlanner.plan(track, mastery, gaps, satisfied).map { planned ->
            remainingMinutes[planned.topic.id]?.let { planned.copy(minutes = it.coerceAtLeast(1)) } ?: planned
        }
        val days = ChronoUnit.DAYS.between(startDate, profile.deadline).toInt().coerceAtLeast(0)
        return Scheduler.schedule(
            path, track.graph, startDate, days, profile.dailyMinutes,
            firstDayMinutes = profile.dailyMinutes - firstDayUsedMinutes,
        )
    }
}
