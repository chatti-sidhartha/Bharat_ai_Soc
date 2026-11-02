package com.example.learnedge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BktTest {
    private val eps = 1e-9

    @Test
    fun correctAnswer_matchesHandComputedValue() {
        // posterior = 0.3*0.9 / (0.3*0.9 + 0.7*0.2) = 0.27 / 0.41
        val posterior = 0.27 / 0.41
        val expected = posterior + (1 - posterior) * 0.15
        assertEquals(expected, Bkt.update(0.3, correct = true), eps)
    }

    @Test
    fun wrongAnswer_matchesHandComputedValue() {
        // posterior = 0.3*0.1 / (0.3*0.1 + 0.7*0.8) = 0.03 / 0.59
        val posterior = 0.03 / 0.59
        val expected = posterior + (1 - posterior) * 0.15
        assertEquals(expected, Bkt.update(0.3, correct = false), eps)
    }

    @Test
    fun correctAnswer_raisesMastery_wrongAnswer_lowersIt() {
        val pL = 0.5
        assertTrue(Bkt.update(pL, correct = true) > pL)
        assertTrue(Bkt.update(pL, correct = false) < pL)
    }

    @Test
    fun zeroPrior_wrongAnswer_givesLearnRate() {
        assertEquals(0.15, Bkt.update(0.0, correct = false), eps)
    }

    @Test
    fun fullMastery_staysAtOne() {
        assertEquals(1.0, Bkt.update(1.0, correct = true), eps)
        assertEquals(1.0, Bkt.update(1.0, correct = false), eps)
    }

    @Test
    fun repeatedCorrectAnswers_reachMastery() {
        val pL = Bkt.updateAll(0.1, List(6) { true })
        assertTrue("P(L) was $pL", Bkt.isMastered(pL))
    }

    @Test
    fun updateAll_appliesAnswersInOrder() {
        val stepwise = Bkt.update(Bkt.update(0.4, true), false)
        assertEquals(stepwise, Bkt.updateAll(0.4, listOf(true, false)), eps)
    }

    @Test
    fun masteryThreshold_isInclusive() {
        assertTrue(Bkt.isMastered(0.95))
        assertFalse(Bkt.isMastered(0.9499))
    }

    @Test
    fun customParams_areUsed() {
        val params = BktParams(learn = 0.0, slip = 0.0, guess = 0.0)
        // No slip, no guess: a correct answer proves knowledge, a wrong one disproves it.
        assertEquals(1.0, Bkt.update(0.2, true, params), eps)
        assertEquals(0.0, Bkt.update(0.2, false, params), eps)
    }

    @Test
    fun invalidInputs_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { Bkt.update(1.5, true) }
        assertThrows(IllegalArgumentException::class.java) { BktParams(slip = 1.0) }
        assertThrows(IllegalArgumentException::class.java) { BktParams(learn = -0.1) }
    }
}
