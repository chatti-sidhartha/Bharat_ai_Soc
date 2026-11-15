package com.example.learnedge.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.min

object GoalProgress {
    /**
     * Share of the learner's goal reached, 0..1: the weighted levels reached (capped at each skill's
     * target) over the weighted targets, i.e. sum(w * min(level, target)) / sum(w * target).
     */
    fun overall(gaps: Collection<SkillGap>): Double {
        val target = gaps.sumOf { it.skill.weight * it.skill.targetLevel }
        if (target <= 0.0) return 1.0
        return gaps.sumOf { it.skill.weight * min(it.currentLevel, it.skill.targetLevel.toDouble()) } / target
    }
}

/** One study block as the day log sees it. */
data class LoggedItem(
    /** The day the block is (or was) planned for, or null for a deferred topic. */
    val plannedDay: LocalDate?,
    /** The day it was marked done, or null if it is not done. */
    val doneDay: LocalDate?,
    val minutes: Int,
)

data class DayMinutes(val date: LocalDate, val doneMinutes: Int, val plannedMinutes: Int)

object StudyLog {
    /**
     * Minutes done and planned for each of the [days] days ending on [today], oldest first. Done minutes
     * are counted on the day a block was marked done, planned minutes on the day it was scheduled for.
     *
     * Limit: the plan only keeps blocks that were done or are still ahead, so for a past day the planned
     * minutes are the minutes that were actually done there.
     */
    fun lastDays(today: LocalDate, days: Int, items: List<LoggedItem>): List<DayMinutes> =
        (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            DayMinutes(
                date = date,
                doneMinutes = items.filter { it.doneDay == date }.sumOf { it.minutes },
                plannedMinutes = items.filter { it.plannedDay == date }.sumOf { it.minutes },
            )
        }
}

/** Average mastery of the quizzed topics after the quizzes of one day. */
data class TimelinePoint(val date: LocalDate, val averageMastery: Double, val topicsQuizzed: Int, val isBaseline: Boolean)

object MasteryTimeline {
    /**
     * The average P(L) over all topics that were ever quizzed, after each day with quiz attempts,
     * preceded by a baseline point: the same topics at their mastery before their first attempt. A
     * topic keeps its baseline value until it is quizzed, so the average only moves when mastery does.
     * Attempts of [reported] questions are ignored. Empty if there are no attempts.
     */
    fun build(attempts: List<QuizAttempt>, reported: Set<String>, zone: ZoneId = ZoneId.systemDefault()): List<TimelinePoint> {
        val valid = attempts.filter { it.questionId !in reported }.sortedBy { it.timestamp }
        if (valid.isEmpty()) return emptyList()

        fun day(a: QuizAttempt) = Instant.ofEpochMilli(a.timestamp).atZone(zone).toLocalDate()
        val baseline = valid.groupBy { it.topicId }.mapValues { (_, list) -> list.first().pLBefore }
        val points = ArrayList<TimelinePoint>()
        points += TimelinePoint(day(valid.first()), baseline.values.average(), baseline.size, isBaseline = true)

        val latest = HashMap(baseline)
        for ((date, dayAttempts) in valid.groupBy { day(it) }) {
            dayAttempts.forEach { latest[it.topicId] = it.pLAfter }
            points += TimelinePoint(date, latest.values.average(), latest.size, isBaseline = false)
        }
        return points
    }
}
