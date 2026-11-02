package com.example.learnedge.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidationTest {
    private val today = LocalDate.of(2026, 11, 3)
    private val track = Track(
        "t", "Track",
        listOf(Skill("java", "Java", 8, 1.0), Skill("sql", "SQL", 7, 1.0)),
        KnowledgeGraph(listOf(Topic("j", "J", "java", 30), Topic("s", "S", "sql", 30)), emptyMap()),
    )
    private val ratings = mapOf("java" to 6, "sql" to 4)

    private fun validate(
        track: Track? = this.track,
        ratings: Map<String, Int> = this.ratings,
        minutes: Int? = 120,
        deadline: LocalDate? = today.plusDays(60),
    ) = ProfileValidation.validate(track, ratings, minutes, deadline, today)

    @Test
    fun validInput_hasNoErrors() {
        assertTrue(validate().isValid)
    }

    @Test
    fun deadline_mustBeAfterToday() {
        assertNotNull(validate(deadline = today).deadline)
        assertNotNull(validate(deadline = today.minusDays(1)).deadline)
        assertNotNull(validate(deadline = null).deadline)
        assertNull(validate(deadline = today.plusDays(1)).deadline)
    }

    @Test
    fun dailyMinutes_mustBePositiveAndRealistic() {
        assertNotNull(validate(minutes = 0).dailyMinutes)
        assertNotNull(validate(minutes = -30).dailyMinutes)
        assertNotNull(validate(minutes = null).dailyMinutes)
        assertNotNull(validate(minutes = ProfileValidation.MAX_DAILY_MINUTES + 1).dailyMinutes)
        assertNull(validate(minutes = 1).dailyMinutes)
    }

    @Test
    fun track_isRequired() {
        val errors = validate(track = null)
        assertNotNull(errors.track)
        assertFalse(errors.isValid)
    }

    @Test
    fun everySkillOfTheTrack_mustBeRatedWithin0To10() {
        assertEquals("Rate every skill: SQL.", validate(ratings = mapOf("java" to 6)).ratings)
        assertNotNull(validate(ratings = mapOf("java" to 11, "sql" to 4)).ratings)
        assertNull(validate(ratings = mapOf("java" to 0, "sql" to 10)).ratings)
    }
}
