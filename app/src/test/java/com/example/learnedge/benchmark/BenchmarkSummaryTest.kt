package com.example.learnedge.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkSummaryTest {
    private val eps = 1e-9

    private fun record(label: String, ttft: Long?, tps: Double, tokens: Int, pss: Double, temp: Double?) =
        BenchmarkRecord(
            timestamp = "t", engine = "Mock", promptLabel = label, run = 1, promptChars = 10,
            loadTimeMs = 600, timeToFirstTokenMs = ttft, decodeTokensPerSecond = tps, totalTokens = tokens,
            totalTimeMs = tokens * 100L, peakPssMb = pss, batteryTempStartC = null, batteryTempEndC = temp,
        )

    @Test
    fun stat_computesMeanMinMax() {
        val stat = Stat.of(listOf(2.0, 4.0, 9.0))!!
        assertEquals(5.0, stat.mean, eps)
        assertEquals(2.0, stat.min, eps)
        assertEquals(9.0, stat.max, eps)
    }

    @Test
    fun stat_ofEmptyList_isNull() {
        assertNull(Stat.of(emptyList()))
    }

    @Test
    fun stat_format_showsMeanAndRange() {
        assertEquals("18.2 (17.9–18.6)", Stat(18.23, 17.9, 18.6).format(1))
    }

    @Test
    fun summarize_groupsByLabelInFirstSeenOrder() {
        val summaries = BenchmarkSummary.summarize(
            listOf(
                record("short", 300, 20.0, 30, 1200.0, 33.0),
                record("long", 900, 16.0, 300, 1300.0, 35.0),
                record("short", 500, 18.0, 40, 1250.0, 34.0),
            )
        )
        assertEquals(listOf("short", "long"), summaries.map { it.promptLabel })

        val short = summaries[0]
        assertEquals(2, short.runs)
        assertEquals(400.0, short.timeToFirstTokenMs!!.mean, eps)
        assertEquals(19.0, short.decodeTokensPerSecond!!.mean, eps)
        assertEquals(30.0, short.totalTokens!!.min, eps)
        assertEquals(40.0, short.totalTokens!!.max, eps)
        assertEquals(1250.0, short.peakPssMb!!.max, eps)
        assertEquals(33.5, short.batteryTempC!!.mean, eps)
    }

    @Test
    fun summarize_skipsMissingValues() {
        val summary = BenchmarkSummary.summarize(
            listOf(record("short", null, 20.0, 30, 1200.0, null), record("short", 400, 18.0, 30, 1200.0, null))
        ).single()
        assertEquals(400.0, summary.timeToFirstTokenMs!!.mean, eps)
        assertNull(summary.batteryTempC)
    }

    @Test
    fun formatTable_hasHeaderAndOneLinePerPrompt() {
        val lines = BenchmarkSummary.formatTable(
            "Mock",
            BenchmarkSummary.summarize(listOf(record("short", 300, 20.0, 30, 1200.0, null))),
        )
        assertEquals(2, lines.size)
        assertEquals(
            "short | 1 | 300 (300–300) | 20.0 (20.0–20.0) | 30 (30–30) | 3000 (3000–3000) | 1200 (1200–1200) | –",
            lines[1],
        )
    }
}
