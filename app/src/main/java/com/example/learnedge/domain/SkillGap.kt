package com.example.learnedge.domain

import kotlin.math.max

/** How far a skill is from its target. [gap] is g = w * max(0, target - current) / 10. */
data class SkillGap(
    val skill: Skill,
    val currentLevel: Double,
    val gap: Double,
)

object SkillGaps {
    /**
     * Current level (0–10) of [skillId]: mean P(L) of its topics that have mastery data × 10, or
     * the learner's self-rating when no topic has data yet.
     */
    fun currentLevel(track: Track, skillId: String, mastery: Map<String, Double>, selfRatings: Map<String, Int>): Double {
        val values = track.topicsOf(skillId).mapNotNull { mastery[it.id] }
        return if (values.isEmpty()) (selfRatings[skillId] ?: 0).toDouble() else values.average() * 10
    }

    fun gap(skill: Skill, currentLevel: Double): Double =
        skill.weight * max(0.0, skill.targetLevel - currentLevel) / 10

    fun compute(track: Track, mastery: Map<String, Double>, selfRatings: Map<String, Int>): Map<String, SkillGap> =
        track.skills.associate { skill ->
            val current = currentLevel(track, skill.id, mastery, selfRatings)
            skill.id to SkillGap(skill, current, gap(skill, current))
        }
}
