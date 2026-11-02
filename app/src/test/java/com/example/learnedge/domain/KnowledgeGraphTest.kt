package com.example.learnedge.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeGraphTest {
    private val a = Topic("a", "Variables")
    private val b = Topic("b", "Loops")
    private val c = Topic("c", "Functions")

    @Test
    fun validGraph_exposesPrerequisitesAndDependents() {
        val graph = KnowledgeGraph(listOf(a, b, c), mapOf("b" to setOf("a"), "c" to setOf("a", "b")))
        assertEquals(setOf("a", "b"), graph.prerequisitesOf("c"))
        assertEquals(emptySet<String>(), graph.prerequisitesOf("a"))
        assertEquals(setOf("b", "c"), graph.dependentsOf("a"))
    }

    @Test
    fun cycle_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeGraph(listOf(a, b, c), mapOf("a" to setOf("c"), "b" to setOf("a"), "c" to setOf("b")))
        }
    }

    @Test
    fun selfLoop_isRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeGraph(listOf(a), mapOf("a" to setOf("a")))
        }
    }

    @Test
    fun unknownIds_areRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeGraph(listOf(a), mapOf("a" to setOf("zzz")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeGraph(listOf(a), mapOf("zzz" to setOf("a")))
        }
    }

    @Test
    fun duplicateIds_areRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeGraph(listOf(a, a.copy(name = "Other")), emptyMap())
        }
    }

    @Test
    fun invalidTopicAndProgress_areRejected() {
        assertThrows(IllegalArgumentException::class.java) { Topic("x", "X", weight = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { TopicProgress("x", mastery = 1.2) }
        assertThrows(IllegalArgumentException::class.java) {
            TopicProgress("x", mastery = 0.5, correctAnswers = 3, totalAnswers = 2)
        }
    }

    @Test
    fun progress_usesMasteryThreshold() {
        assertTrue(TopicProgress("x", mastery = 0.96).isMastered)
        assertFalse(TopicProgress("x", mastery = 0.5).isMastered)
    }
}
