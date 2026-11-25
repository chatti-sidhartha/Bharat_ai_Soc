package com.example.learnedge.domain

import java.util.Locale

/** Android's PowerManager thermal status, in the same order as the platform constants (0 = none ... 6 = shutdown). */
enum class ThermalStatus {
    NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN;

    fun atLeast(other: ThermalStatus) = ordinal >= other.ordinal

    companion object {
        /** Maps the platform int; an unknown negative value (status unavailable) counts as none. */
        fun fromAndroid(value: Int): ThermalStatus = entries.getOrNull(value) ?: if (value < 0) NONE else SHUTDOWN
    }
}

/** Every threshold of the thermal policy in one place, so it can be tuned (and tested) without touching logic. */
data class GovernorConfig(
    /** From this status on, background work pauses between items. */
    val pauseAt: ThermalStatus = ThermalStatus.MODERATE,
    /** From this status on, background work stops and is rescheduled. */
    val stopAt: ThermalStatus = ThermalStatus.SEVERE,
    val pauseBetweenItemsMs: Long = 60_000,
    /** Battery temperature (deg C) at which background work pauses / stops, independent of the thermal status. */
    val batteryTempPauseC: Double = 40.0,
    val batteryTempStopC: Double = 43.0,
    val minBatteryPercent: Int = 20,
    val requireCharging: Boolean = true,
    /** How long a stopped background run waits before it tries again. */
    val rescheduleDelayMinutes: Long = 15,
    /** One background run ends by itself after this long and re-enqueues if work remains (WorkManager allows about 10 min). */
    val runBudgetMs: Long = 8 * 60_000,
    /** In the foreground, from this status on cached or bundled content is preferred over new generation. */
    val foregroundPreferCachedAt: ThermalStatus = ThermalStatus.MODERATE,
    val normalMaxOutputChars: Int = 1_500,
    val foregroundMaxOutputChars: Int = 700,
)

/** What the governor looks at. [batteryTempC] is null when the phone does not report it. */
data class GovernorInputs(
    val thermal: ThermalStatus,
    val batteryTempC: Double?,
    val batteryPercent: Int,
    val charging: Boolean,
)

sealed interface BackgroundDecision {
    data object Run : BackgroundDecision

    /** Wait [millis] before the next item. */
    data class Pause(val millis: Long, val reason: String) : BackgroundDecision

    /** End this run and reschedule it. */
    data class Stop(val reason: String) : BackgroundDecision
}

data class ForegroundDecision(
    /** Show cached or bundled content instead of starting a new generation. */
    val preferCachedOrBundled: Boolean,
    /** Upper bound on the characters one generation may produce (a cap on the token limit). */
    val maxOutputChars: Int,
)

/** The thermal and power policy for on-device generation. Pure: the same inputs always give the same decision. */
class ThermalGovernor(val config: GovernorConfig = GovernorConfig()) {

    /** Decision before generating the next background item. */
    fun background(inputs: GovernorInputs): BackgroundDecision {
        if (config.requireCharging && !inputs.charging) return BackgroundDecision.Stop("not charging")
        if (inputs.batteryPercent < config.minBatteryPercent) return BackgroundDecision.Stop("battery low (${inputs.batteryPercent}%)")
        if (inputs.thermal.atLeast(config.stopAt)) return BackgroundDecision.Stop("thermal status ${inputs.thermal}")
        val temp = inputs.batteryTempC
        if (temp != null && temp >= config.batteryTempStopC) return BackgroundDecision.Stop("battery ${degrees(temp)}")
        if (inputs.thermal.atLeast(config.pauseAt)) return BackgroundDecision.Pause(config.pauseBetweenItemsMs, "thermal status ${inputs.thermal}")
        if (temp != null && temp >= config.batteryTempPauseC) return BackgroundDecision.Pause(config.pauseBetweenItemsMs, "battery ${degrees(temp)}")
        return BackgroundDecision.Run
    }

    /** Decision when the learner asks for generation in the app: protect the phone, never block the learner. */
    fun foreground(inputs: GovernorInputs): ForegroundDecision {
        val hot = inputs.thermal.atLeast(config.foregroundPreferCachedAt) ||
            (inputs.batteryTempC != null && inputs.batteryTempC >= config.batteryTempStopC)
        return ForegroundDecision(preferCachedOrBundled = hot, maxOutputChars = if (hot) config.foregroundMaxOutputChars else config.normalMaxOutputChars)
    }

    private fun degrees(value: Double) = String.format(Locale.US, "%.1f C", value)
}

/** The topics of one plan day, in plan order. Only blocks that are not done. */
data class PlanDay(val epochDay: Long, val topicIds: List<String>)

/** One unit of preparation work. */
sealed interface PrepItem {
    data class Explanation(val topicId: String, val band: LevelBand) : PrepItem

    /** The summary and tips of a whole plan day. */
    data class Tips(val epochDay: Long) : PrepItem
}

object PreparationPlanner {
    /** How many upcoming plan days are prepared. */
    const val HORIZON_DAYS = 2

    /** The next [HORIZON_DAYS] days that have study blocks, starting today. */
    private fun upcoming(days: List<PlanDay>, today: Long) =
        days.filter { it.epochDay >= today && it.topicIds.isNotEmpty() }.sortedBy { it.epochDay }.take(HORIZON_DAYS)

    /** Every topic that is in the preparation horizon, once, in plan order. */
    fun horizonTopics(days: List<PlanDay>, today: Long): List<String> = upcoming(days, today).flatMap { it.topicIds }.distinct()

    /**
     * What to prepare, in the order to do it: first one explanation per topic of the next two plan days
     * (in plan order, each topic once) unless a valid cached one exists for the learner's current level
     * band and the current prompt version, then tomorrow's tips if tomorrow has study blocks and its tips are missing.
     *
     * @param bandOf the learner's current level band for a topic.
     * @param hasExplanation true if a valid cached explanation exists for that topic and band (current prompt version).
     * @param hasTips true if valid tips are stored for that day.
     */
    fun plan(
        days: List<PlanDay>,
        today: Long,
        bandOf: (String) -> LevelBand,
        hasExplanation: (topicId: String, band: LevelBand) -> Boolean,
        hasTips: (epochDay: Long) -> Boolean,
    ): List<PrepItem> {
        val explanations = horizonTopics(days, today)
            .map { it to bandOf(it) }
            .filterNot { (topic, band) -> hasExplanation(topic, band) }
            .map { (topic, band) -> PrepItem.Explanation(topic, band) }
        val tomorrow = today + 1
        val tips = days.firstOrNull { it.epochDay == tomorrow && it.topicIds.isNotEmpty() }
            ?.takeIf { !hasTips(tomorrow) }
            ?.let { PrepItem.Tips(tomorrow) }
        return explanations + listOfNotNull(tips)
    }
}
