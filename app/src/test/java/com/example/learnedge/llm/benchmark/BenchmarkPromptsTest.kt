package com.example.learnedge.llm.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkPromptsTest {
    @Test
    fun schedule_runsEveryPromptThreeTimes() {
        val schedule = BenchmarkPrompts.schedule()
        assertEquals(9, schedule.size)
        BenchmarkPrompts.ALL.forEach { prompt ->
            assertEquals(listOf(1, 2, 3), schedule.filter { it.first == prompt }.map { it.second })
        }
    }

    @Test
    fun schedule_interleavesPromptsInRounds() {
        assertEquals(
            listOf("short", "medium", "long", "short", "medium", "long"),
            BenchmarkPrompts.schedule(runs = 2).map { it.first.label },
        )
    }

    @Test
    fun prompts_haveDistinctLabelsAndIncreasingLength() {
        val all = BenchmarkPrompts.ALL
        assertEquals(listOf("short", "medium", "long"), all.map { it.label })
        assertTrue(all.zipWithNext().all { (a, b) -> a.text.length < b.text.length })
    }
}
