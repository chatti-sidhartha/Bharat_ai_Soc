package com.example.learnedge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeGraphTest {
    private fun topic(id: String) = Topic(id, id.uppercase(), skillId = "s", estimatedMinutes = 30)
    private val a = topic("a")
    private val b = topic("b")
    private val c = topic("c")
    private val d = topic("d")

    // a -> b -> d, a -> c -> d
    private val diamond = KnowledgeGraph(
        listOf(a, b, c, d),
        mapOf("b" to setOf("a"), "c" to setOf("a"), "d" to setOf("b", "c")),
    )

    @Test
    fun prerequisitesAndDependents_areDirectEdges() {
        assertEquals(setOf("b", "c"), diamond.getPrerequisites("d"))
        assertEquals(emptySet<String>(), diamond.getPrerequisites("a"))
        assertEquals(setOf("b", "c"), diamond.getDependents("a"))
        assertEquals(emptySet<String>(), diamond.getDependents("d"))
    }

    @Test
    fun descendants_areTransitiveAndCountedOnce() {
        assertEquals(setOf("b", "c", "d"), diamond.getDescendants("a"))
        assertEquals(3, diamond.descendantCount("a"))
        assertEquals(1, diamond.descendantCount("b"))
        assertEquals(0, diamond.descendantCount("d"))
    }

    @Test
    fun cycle_isRejectedWithTheTopicsInvolved() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            KnowledgeGraph(listOf(a, b, c), mapOf("a" to setOf("c"), "b" to setOf("a"), "c" to setOf("b")))
        }
        assertTrue(e.message!!, e.message!!.contains("cycle"))
        assertTrue(e.message!!, listOf("a", "b", "c").all { it in e.message!! })
    }

    @Test
    fun selfLoop_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeGraph(listOf(a), mapOf("a" to setOf("a")))
        }
    }

    @Test
    fun unknownIds_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { KnowledgeGraph(listOf(a), mapOf("a" to setOf("zzz"))) }
        assertThrows(IllegalArgumentException::class.java) { KnowledgeGraph(listOf(a), mapOf("zzz" to setOf("a"))) }
    }

    @Test
    fun duplicateIds_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { KnowledgeGraph(listOf(a, a.copy(name = "Other")), emptyMap()) }
    }

    @Test
    fun unknownTopicLookup_throws() {
        assertFalse("zzz" in diamond)
        assertThrows(NoSuchElementException::class.java) { diamond.getPrerequisites("zzz") }
    }

    @Test
    fun track_rejectsTopicWithUnknownSkill() {
        val graph = KnowledgeGraph(listOf(Topic("x", "X", skillId = "missing", estimatedMinutes = 10)), emptyMap())
        assertThrows(IllegalArgumentException::class.java) { Track("t", "T", listOf(Skill("s", "S", 5, 1.0)), graph) }
    }

    @Test
    fun invalidValues_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { Skill("s", "S", targetLevel = 11, weight = 1.0) }
        assertThrows(IllegalArgumentException::class.java) { Skill("s", "S", targetLevel = 5, weight = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { Topic("x", "X", "s", estimatedMinutes = 0) }
        assertThrows(IllegalArgumentException::class.java) { TopicProgress("x", mastery = 1.2) }
        assertThrows(IllegalArgumentException::class.java) { TopicProgress("x", mastery = 0.5, attempts = 2, correct = 3) }
    }

    @Test
    fun progress_usesMasteryThreshold() {
        assertTrue(TopicProgress("x", mastery = 0.96).isMastered)
        assertFalse(TopicProgress("x", mastery = 0.5).isMastered)
    }
}
