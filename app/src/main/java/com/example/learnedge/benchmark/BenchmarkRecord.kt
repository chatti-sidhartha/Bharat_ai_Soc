package com.example.learnedge.benchmark

import java.util.Locale

/** Measurements for one generation. Pure data so the CSV format can be unit-tested. */
data class BenchmarkRecord(
    val timestamp: String,
    val engine: String,
    val promptLabel: String,
    val run: Int,
    /** Length of the full formatted prompt (template + history) the model had to process. */
    val promptChars: Int,
    val loadTimeMs: Long?,
    val timeToFirstTokenMs: Long?,
    val decodeTokensPerSecond: Double,
    val totalTokens: Int,
    val totalTimeMs: Long,
    val peakPssMb: Double,
    val batteryTempStartC: Double?,
    val batteryTempEndC: Double?,
) {
    fun toCsvRow(): String = listOf(
        csvField(timestamp),
        csvField(engine),
        csvField(promptLabel),
        run.toString(),
        promptChars.toString(),
        loadTimeMs?.toString().orEmpty(),
        timeToFirstTokenMs?.toString().orEmpty(),
        formatDecimal(decodeTokensPerSecond, 2),
        totalTokens.toString(),
        totalTimeMs.toString(),
        formatDecimal(peakPssMb, 1),
        batteryTempStartC?.let { formatDecimal(it, 1) }.orEmpty(),
        batteryTempEndC?.let { formatDecimal(it, 1) }.orEmpty(),
    ).joinToString(",")

    companion object {
        const val CSV_HEADER =
            "timestamp,engine,prompt,run,prompt_chars,load_ms,ttft_ms,decode_tok_per_s,total_tokens," +
                "total_ms,peak_pss_mb,battery_temp_start_c,battery_temp_end_c"

        /** Prompt label for normal chat messages; their run number is 0. */
        const val CHAT_LABEL = "chat"
    }
}

internal fun formatDecimal(value: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", value)

/** Quotes a CSV field when it contains a comma, quote or line break (RFC 4180). */
internal fun csvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }
