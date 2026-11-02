package com.example.learnedge.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkRecordTest {
    private val record = BenchmarkRecord(
        timestamp = "2026-11-03T02:10:00",
        engine = "On-device",
        promptLabel = "short",
        run = 1,
        promptChars = 250,
        loadTimeMs = 4200,
        timeToFirstTokenMs = 380,
        decodeTokensPerSecond = 18.2049,
        totalTokens = 34,
        totalTimeMs = 2240,
        peakPssMb = 1290.46,
        batteryTempStartC = 33.9,
        batteryTempEndC = 34.1,
    )

    @Test
    fun row_matchesHeaderColumnOrder() {
        assertEquals(
            "2026-11-03T02:10:00,On-device,short,1,250,4200,380,18.20,34,2240,1290.5,33.9,34.1",
            record.toCsvRow(),
        )
    }

    @Test
    fun row_hasSameColumnCountAsHeader() {
        val headerColumns = BenchmarkRecord.CSV_HEADER.split(",").size
        assertEquals(headerColumns, record.toCsvRow().split(",").size)
        assertEquals(headerColumns, record.copy(loadTimeMs = null, batteryTempEndC = null).toCsvRow().split(",").size)
    }

    @Test
    fun missingValues_areEmptyCells() {
        val row = record.copy(loadTimeMs = null, timeToFirstTokenMs = null, batteryTempStartC = null, batteryTempEndC = null)
            .toCsvRow()
        assertEquals("2026-11-03T02:10:00,On-device,short,1,250,,,18.20,34,2240,1290.5,,", row)
    }

    @Test
    fun specialCharacters_areQuoted() {
        assertEquals("plain", csvField("plain"))
        assertEquals("\"a,b\"", csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", csvField("say \"hi\""))
        assertEquals("\"two\nlines\"", csvField("two\nlines"))
    }
}
