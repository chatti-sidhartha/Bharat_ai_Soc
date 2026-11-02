package com.example.learnedge.benchmark

data class Stat(val mean: Double, val min: Double, val max: Double) {
    /** "mean (min–max)" with the given number of decimals. */
    fun format(decimals: Int): String =
        "${formatDecimal(mean, decimals)} (${formatDecimal(min, decimals)}–${formatDecimal(max, decimals)})"

    companion object {
        fun of(values: List<Double>): Stat? =
            if (values.isEmpty()) null else Stat(values.average(), values.min(), values.max())
    }
}

/** Aggregated runs of one benchmark prompt. A metric is null when no run reported it. */
data class PromptSummary(
    val promptLabel: String,
    val runs: Int,
    val timeToFirstTokenMs: Stat?,
    val decodeTokensPerSecond: Stat?,
    val totalTokens: Stat?,
    val totalTimeMs: Stat?,
    val peakPssMb: Stat?,
    val batteryTempC: Stat?,
)

object BenchmarkSummary {
    /** Groups records by prompt label, keeping the order in which labels first appear. */
    fun summarize(records: List<BenchmarkRecord>): List<PromptSummary> =
        records.groupBy { it.promptLabel }.map { (label, runs) ->
            PromptSummary(
                promptLabel = label,
                runs = runs.size,
                timeToFirstTokenMs = Stat.of(runs.mapNotNull { it.timeToFirstTokenMs?.toDouble() }),
                decodeTokensPerSecond = Stat.of(runs.map { it.decodeTokensPerSecond }),
                totalTokens = Stat.of(runs.map { it.totalTokens.toDouble() }),
                totalTimeMs = Stat.of(runs.map { it.totalTimeMs.toDouble() }),
                peakPssMb = Stat.of(runs.map { it.peakPssMb }),
                batteryTempC = Stat.of(runs.mapNotNull { it.batteryTempEndC }),
            )
        }

    /** Plain-text lines for logcat: a header, then one line per prompt with "mean (min–max)" cells. */
    fun formatTable(engine: String, summaries: List<PromptSummary>): List<String> =
        listOf("engine=$engine | prompt | runs | ttft_ms | decode_tok/s | tokens | total_ms | peak_pss_mb | battery_c") +
            summaries.map { s ->
                listOf(
                    s.promptLabel,
                    s.runs.toString(),
                    s.timeToFirstTokenMs.cell(0),
                    s.decodeTokensPerSecond.cell(1),
                    s.totalTokens.cell(0),
                    s.totalTimeMs.cell(0),
                    s.peakPssMb.cell(0),
                    s.batteryTempC.cell(1),
                ).joinToString(" | ")
            }

    private fun Stat?.cell(decimals: Int): String = this?.format(decimals) ?: "–"
}
