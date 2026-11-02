package com.example.learnedge.domain

import java.time.LocalDate

/** What the learner told us at onboarding. [selfRatings] maps skill id to a 0–10 self-assessment. */
data class LearnerProfile(
    val trackId: String,
    val interests: String,
    val selfRatings: Map<String, Int>,
    val dailyMinutes: Int,
    val deadline: LocalDate,
    val diagnosticCompleted: Boolean = false,
)

/** Field-level validation messages; null means the field is fine. */
data class ProfileErrors(
    val track: String? = null,
    val ratings: String? = null,
    val dailyMinutes: String? = null,
    val deadline: String? = null,
) {
    val isValid: Boolean get() = listOf(track, ratings, dailyMinutes, deadline).all { it == null }
}

object ProfileValidation {
    const val MAX_DAILY_MINUTES = 16 * 60

    /**
     * Checks raw onboarding input. [track] is the chosen track (null if none), [dailyMinutes] is null
     * when the text field doesn't hold a whole number.
     */
    fun validate(
        track: Track?,
        selfRatings: Map<String, Int>,
        dailyMinutes: Int?,
        deadline: LocalDate?,
        today: LocalDate,
    ): ProfileErrors = ProfileErrors(
        track = if (track == null) "Choose a goal." else null,
        ratings = track?.let { t ->
            val missing = t.skills.filter { it.id !in selfRatings }
            val outOfRange = selfRatings.filterValues { it !in 0..10 }.keys
            when {
                missing.isNotEmpty() -> "Rate every skill: ${missing.joinToString { it.name }}."
                outOfRange.isNotEmpty() -> "Ratings must be between 0 and 10."
                else -> null
            }
        },
        dailyMinutes = when {
            dailyMinutes == null -> "Enter your daily study time in minutes."
            dailyMinutes <= 0 -> "Daily study time must be more than 0 minutes."
            dailyMinutes > MAX_DAILY_MINUTES -> "Daily study time can be at most $MAX_DAILY_MINUTES minutes."
            else -> null
        },
        deadline = when {
            deadline == null -> "Pick a deadline."
            !deadline.isAfter(today) -> "The deadline must be in the future."
            else -> null
        },
    )
}
