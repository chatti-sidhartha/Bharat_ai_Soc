package com.example.learnedge.domain

import java.util.Locale

/**
 * The numbers behind one planning decision, read from the same functions the planner uses, so the
 * explanation can never disagree with the plan: skill gap g, priority p = g * (1 + 0.1 * dependents),
 * how many later topics this one unlocks, and how many of its prerequisites are mastered.
 */
data class PlanReason(
    val topicId: String,
    val topicName: String,
    val skillName: String,
    /** g = w * max(0, target - current) / 10 for the topic's skill. */
    val skillGap: Double,
    /** 1 = the skill with the largest gap. */
    val gapRank: Int,
    val priority: Double,
    /** Topics that directly or indirectly depend on this one (the planner's descendant count). */
    val dependents: Int,
    val prerequisites: Int,
    val prerequisitesMastered: Int,
)

object PlanReasons {
    /** A reason for every topic of [track], from the current mastery. Planning itself is not touched. */
    fun compute(track: Track, mastery: Map<String, Double>, selfRatings: Map<String, Int>): Map<String, PlanReason> {
        val gaps = SkillGaps.compute(track, mastery, selfRatings)
        return track.graph.topics.associate { topic ->
            val skillGap = gaps.getValue(topic.skillId)
            val prerequisites = track.graph.getPrerequisites(topic.id)
            topic.id to PlanReason(
                topicId = topic.id,
                topicName = topic.name,
                skillName = skillGap.skill.name,
                skillGap = skillGap.gap,
                gapRank = 1 + gaps.values.count { it.gap > skillGap.gap },
                priority = PathPlanner.priority(track, topic, gaps),
                dependents = track.graph.descendantCount(topic.id),
                prerequisites = prerequisites.size,
                prerequisitesMastered = prerequisites.count { Bkt.isMastered(mastery[it] ?: 0.0) },
            )
        }
    }
}

/** Turns a [PlanReason] into two or three plain sentences from fixed templates. No model is involved. */
object ReasonText {
    /**
     * @param whenText where the topic sits in the plan, e.g. "today" or "on Tue 17 Nov"; ignored when [deferredReason] is set.
     * @param deferredReason the scheduler's reason when the topic was left out of the plan, otherwise null.
     */
    fun render(reason: PlanReason, whenText: String, deferredReason: String? = null): String {
        val first = if (deferredReason != null) {
            "Deferred: ${deferredReason.trim().trimEnd('.')}."
        } else {
            scheduledSentence(reason, whenText)
        }
        val third = if (deferredReason != null) {
            "Add study time or move the deadline to bring it back into the plan."
        } else {
            prerequisiteSentence(reason)
        }
        return listOf(first, prioritySentence(reason), third).joinToString(" ")
    }

    private fun scheduledSentence(r: PlanReason, whenText: String): String =
        if (r.skillGap > 0) {
            "Scheduled $whenText because ${r.skillName} is ${rank(r.gapRank)} (${num(r.skillGap)})."
        } else {
            "Scheduled $whenText because ${r.topicName} is not mastered yet, although ${r.skillName} is already at its target level."
        }

    private fun rank(rank: Int) = when (rank) {
        1 -> "your largest gap"
        2 -> "your second-largest gap"
        3 -> "your third-largest gap"
        else -> "one of your gaps"
    }

    private fun prioritySentence(r: PlanReason): String = when (r.dependents) {
        0 -> "No later topic depends on it, so its priority is just the gap (${num(r.priority)})."
        else -> {
            val topics = if (r.dependents == 1) "1 later topic" else "${r.dependents} later topics"
            "It unlocks $topics, which gives it priority ${num(r.priority)} = ${num(r.skillGap)} × (1 + 0.1 × ${r.dependents})."
        }
    }

    private fun prerequisiteSentence(r: PlanReason): String = when {
        r.prerequisites == 0 -> "It has no prerequisites."
        r.prerequisitesMastered == r.prerequisites -> when (r.prerequisites) {
            1 -> "You have mastered its prerequisite."
            2 -> "You have mastered both prerequisites."
            else -> "You have mastered all ${r.prerequisites} prerequisites."
        }
        r.prerequisitesMastered == 0 -> when (r.prerequisites) {
            1 -> "Its prerequisite is not mastered yet, so that topic is planned before this one."
            else -> "None of its ${r.prerequisites} prerequisites is mastered yet, so they are planned before this topic."
        }
        else -> "${r.prerequisitesMastered} of its ${r.prerequisites} prerequisites are mastered; the others are planned before this topic."
    }

    private fun num(value: Double) = String.format(Locale.US, "%.2f", value)
}
