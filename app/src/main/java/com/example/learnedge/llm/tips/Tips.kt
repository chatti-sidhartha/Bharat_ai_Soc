package com.example.learnedge.llm.tips

import java.time.LocalDate

/** Everything the model may know when writing today's tips. The plan itself is fixed input, never output. */
data class TipInput(
    val date: LocalDate,
    val trackName: String,
    val interests: String,
    val dailyMinutes: Int,
    val deadline: LocalDate,
    /** Biggest skill gaps first. */
    val gaps: List<GapLine>,
    /** Today's topics in plan order. */
    val topics: List<TipTopic>,
) {
    /** Changes only when today's topics change, so tips are regenerated exactly then. */
    val signature: String get() = "${date}|" + topics.joinToString(",") { "${it.id}:${it.minutes}" }
}

data class GapLine(val skillName: String, val currentLevel: Double, val targetLevel: Int)

data class TipTopic(val id: String, val name: String, val description: String, val minutes: Int)

enum class TipSource { AI, RULE_BASED }

data class Tip(val text: String, val source: TipSource)

data class DailyTips(
    val summary: String,
    val summarySource: TipSource,
    /** One tip per topic id of the day. */
    val tips: Map<String, Tip>,
)

/** Result of [TipGenerator.generate]: the tips, how many model calls it took and why attempts failed. */
data class GeneratedTips(
    val tips: DailyTips,
    val modelCalls: Int,
    val failures: List<String>,
)
