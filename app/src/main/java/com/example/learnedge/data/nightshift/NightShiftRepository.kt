package com.example.learnedge.data.nightshift

import android.content.Context
import android.os.SystemClock
import com.example.learnedge.data.content.ContentRepository
import com.example.learnedge.data.content.PrepOutcome
import com.example.learnedge.data.content.PrepResult
import com.example.learnedge.data.curriculum.CurriculumRepository
import com.example.learnedge.data.db.PlanItemEntity
import com.example.learnedge.data.learner.LearnerStateRepository
import com.example.learnedge.data.plan.PlanRepository
import com.example.learnedge.data.profile.ProfileRepository
import com.example.learnedge.data.tips.TipsRepository
import com.example.learnedge.domain.BackgroundDecision
import com.example.learnedge.domain.ForegroundDecision
import com.example.learnedge.domain.GovernorInputs
import com.example.learnedge.domain.LearnerProfile
import com.example.learnedge.domain.LevelBand
import com.example.learnedge.domain.PlanDay
import com.example.learnedge.domain.PrepItem
import com.example.learnedge.domain.PreparationPlanner
import com.example.learnedge.domain.SkillGaps
import com.example.learnedge.domain.ThermalGovernor
import com.example.learnedge.domain.TopicProgress
import com.example.learnedge.domain.Track
import com.example.learnedge.llm.LlmService
import com.example.learnedge.llm.tips.GapLine
import com.example.learnedge.llm.tips.TipInput
import com.example.learnedge.llm.tips.TipTopic
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex

/** Live state of a preparation run, for the "Prepared for you" card. */
data class NightShiftProgress(
    val running: Boolean = false,
    val foreground: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val note: String = "",
)

/** How much of the next two plan days is ready, and when the newest part was prepared. */
data class PreparedSummary(val ready: Int = 0, val total: Int = 0, val preparedAt: Long? = null)

data class RunResult(val saved: Int, val failed: Int, val paused: Int, val remaining: Int, val stopReason: String?)

/**
 * Prepares upcoming lessons ahead of time, one small unit at a time. Each unit is generated, validated and saved
 * before the next one starts, so a run can end at any moment (budget, heat, a request from the learner) and the
 * next run simply carries on from what is already in the cache.
 */
class NightShiftRepository(
    private val appContext: Context,
    private val curriculum: CurriculumRepository,
    private val profiles: ProfileRepository,
    private val learnerState: LearnerStateRepository,
    private val plans: PlanRepository,
    private val content: ContentRepository,
    private val tips: TipsRepository,
    private val llm: LlmService,
    private val sensors: DeviceSensors,
    private val governor: ThermalGovernor,
    private val log: NightShiftLog,
    val prefs: NightShiftPrefs,
) {
    private val runLock = Mutex()
    private val _progress = MutableStateFlow(NightShiftProgress())
    val progress: StateFlow<NightShiftProgress> = _progress.asStateFlow()

    val config get() = governor.config

    /** The thermal policy for requests from the learner, read from the phone right now. */
    fun foregroundPolicy(): ForegroundDecision = governor.foreground(sensors.read())

    fun setEnabled(enabled: Boolean) {
        prefs.setEnabled(enabled)
        if (enabled) NightShiftScheduler.ensureScheduled(appContext) else NightShiftScheduler.cancelAll(appContext)
    }

    fun summary(): Flow<PreparedSummary> = combine(plans.observeItems(), learnerState.observe(), content.observeCachedExplanations()) { items, mastery, rows ->
        val topics = PreparationPlanner.horizonTopics(planDays(items), LocalDate.now().toEpochDay())
        val byKey = rows.associateBy { it.topicId to LevelBand.valueOf(it.levelBand) }
        val ready = topics.mapNotNull { byKey[it to LevelBand.of(mastery[it]?.mastery ?: 0.0)] }
        PreparedSummary(ready.size, topics.size, ready.maxOfOrNull { it.createdAt })
    }

    /**
     * Runs preparation units until nothing is left or the run has to stop. [foreground] = the learner pressed
     * "Prepare now": the same pipeline, but with the foreground thermal policy and no pausing. Otherwise it is a
     * background run: it asks the thermal governor before every item and gives way to the learner at once.
     */
    suspend fun run(foreground: Boolean, budgetMs: Long = governor.config.runBudgetMs): RunResult {
        if (!runLock.tryLock()) return RunResult(0, 0, 0, 0, "another run is active")
        try {
            return doRun(foreground, budgetMs)
        } finally {
            runLock.unlock()
            _progress.value = NightShiftProgress()
        }
    }

    private suspend fun doRun(foreground: Boolean, budgetMs: Long): RunResult {
        val mode = if (foreground) "foreground" else "background"
        val started = SystemClock.elapsedRealtime()
        val failedThisRun = HashSet<String>()
        var saved = 0
        var failed = 0
        var paused = 0
        var total = -1
        var stopReason: String? = null

        while (true) {
            val queue = queue().filterNot { key(it) in failedThisRun }
            if (total < 0) total = queue.size
            _progress.value = NightShiftProgress(true, foreground, saved, maxOf(total, saved + queue.size), label(queue.firstOrNull()))
            if (queue.isEmpty()) break
            if (SystemClock.elapsedRealtime() - started >= budgetMs) { stopReason = "run budget reached"; break }
            if (!foreground && llm.foregroundPending) { stopReason = "the learner is using the model"; break }

            val item = queue.first()
            val before = sensors.read()
            var maxChars = governor.config.normalMaxOutputChars
            if (foreground) {
                if (before.thermal.atLeast(governor.config.stopAt)) { stopReason = "phone too hot (${before.thermal})"; break }
                maxChars = governor.foreground(before).maxOutputChars
            } else {
                when (val decision = governor.background(before)) {
                    BackgroundDecision.Run -> Unit
                    is BackgroundDecision.Stop -> { stopReason = decision.reason; break }
                    is BackgroundDecision.Pause -> {
                        paused++
                        log.append(row(mode, item, PrepOutcome(PrepResult.NO_MODEL, 0, decision.millis / 1000.0), before, before.batteryTempC, "paused"))
                        val left = budgetMs - (SystemClock.elapsedRealtime() - started)
                        if (decision.millis >= left) { stopReason = "paused (${decision.reason}) until the run budget ended"; break }
                        _progress.value = _progress.value.copy(note = "Cooling down: ${decision.reason}")
                        if (!sleepUnlessPreempted(decision.millis)) { stopReason = "the learner is using the model"; break }
                        continue
                    }
                }
            }

            val outcome = prepare(item, foreground, maxChars)
            val after = sensors.read()
            val result = when (outcome.result) {
                PrepResult.SAVED -> "saved"
                PrepResult.FAILED_VALIDATION -> "failed_validation"
                PrepResult.PREEMPTED -> "preempted"
                PrepResult.NO_MODEL -> "no_model"
                PrepResult.ALREADY_READY -> null
            }
            if (result != null) log.append(row(mode, item, outcome, before, after.batteryTempC, result, after.batteryPercent))
            when (outcome.result) {
                PrepResult.SAVED -> saved++
                PrepResult.FAILED_VALIDATION -> { failed++; failedThisRun += key(item) }
                PrepResult.PREEMPTED -> { stopReason = "preempted by a request from the learner"; break }
                PrepResult.NO_MODEL -> { failed++; stopReason = "the model is not ready"; break }
                PrepResult.ALREADY_READY -> Unit
            }
        }
        val remaining = queue().filterNot { key(it) in failedThisRun }.size
        return RunResult(saved, failed, paused, remaining, stopReason)
    }

    private suspend fun prepare(item: PrepItem, foreground: Boolean, maxChars: Int): PrepOutcome = when (item) {
        is PrepItem.Explanation -> content.prepareExplanation(item.topicId, background = !foreground, maxChars = maxChars)
        is PrepItem.Tips -> {
            val profile = profiles.get()
            val input = profile?.let { p -> tipInput(curriculum.track(p.trackId), p, learnerState.get(), plans.observeItems().first(), LocalDate.ofEpochDay(item.epochDay)) }
            if (input == null) PrepOutcome(PrepResult.ALREADY_READY, 0, 0.0) else tips.prepare(input, background = !foreground, maxChars = maxChars)
        }
    }

    /** What is still missing, recomputed from the stored plan and the cache before every item (that is what makes a run resumable). */
    private suspend fun queue(): List<PrepItem> {
        val profile = profiles.get() ?: return emptyList()
        val track = curriculum.track(profile.trackId)
        val items = plans.observeItems().first().filter { it.topicId in track.graph }
        val mastery = learnerState.get()
        val cached = content.cachedExplanations()
        val today = LocalDate.now()
        val tomorrowInput = tipInput(track, profile, mastery, items, today.plusDays(1))
        val tipsStored = tomorrowInput?.let { tips.isStored(it) } ?: true
        return PreparationPlanner.plan(
            days = planDays(items),
            today = today.toEpochDay(),
            bandOf = { LevelBand.of(mastery[it]?.mastery ?: 0.0) },
            hasExplanation = { topic, band -> (topic to band) in cached },
            hasTips = { tipsStored },
        )
    }

    private fun planDays(items: List<PlanItemEntity>): List<PlanDay> =
        items.filter { it.dayEpochDay != null && !it.done }
            .groupBy { it.dayEpochDay!! }
            .map { (day, list) -> PlanDay(day, list.sortedBy { it.position }.map { it.topicId }.distinct()) }

    /** The same tip input the Today screen builds for that day, so stored tips match when the day arrives. */
    private fun tipInput(track: Track, profile: LearnerProfile, mastery: Map<String, TopicProgress>, items: List<PlanItemEntity>, day: LocalDate): TipInput? {
        val dayItems = items.filter { it.dayEpochDay == day.toEpochDay() && !it.done }
        if (dayItems.isEmpty()) return null
        val gaps = SkillGaps.compute(track, mastery.mapValues { it.value.mastery }, profile.selfRatings).values
            .filter { it.gap > 0 }.sortedByDescending { it.gap }.take(3)
            .map { GapLine(it.skill.name, it.currentLevel, it.skill.targetLevel) }
        return TipInput(
            date = day,
            trackName = track.name,
            interests = profile.interests,
            dailyMinutes = profile.dailyMinutes,
            deadline = profile.deadline,
            gaps = gaps,
            topics = dayItems.groupBy { it.topicId }.map { (id, parts) ->
                val topic = track.graph.topic(id)
                TipTopic(id, topic.name, topic.description, parts.sumOf { it.minutes })
            },
        )
    }

    private suspend fun sleepUnlessPreempted(millis: Long): Boolean {
        repeat((millis / 1_000).toInt()) {
            if (llm.foregroundPending) return false
            delay(1_000)
        }
        return true
    }

    private fun key(item: PrepItem) = when (item) {
        is PrepItem.Explanation -> "e:${item.topicId}:${item.band}"
        is PrepItem.Tips -> "t:${item.epochDay}"
    }

    private suspend fun label(item: PrepItem?): String = when (item) {
        null -> ""
        is PrepItem.Tips -> "tomorrow's tips"
        is PrepItem.Explanation -> profiles.get()?.let { curriculum.track(it.trackId).graph.topic(item.topicId).name } ?: item.topicId
    }

    private fun row(
        mode: String,
        item: PrepItem,
        outcome: PrepOutcome,
        before: GovernorInputs,
        tempEnd: Double?,
        result: String,
        percentAfter: Int = before.batteryPercent,
    ) = NightShiftRow(
        mode = mode,
        kind = if (item is PrepItem.Tips) "tips" else "explanation",
        item = when (item) { is PrepItem.Explanation -> item.topicId; is PrepItem.Tips -> "day-${item.epochDay}" },
        tokens = outcome.tokens,
        seconds = outcome.seconds,
        thermal = before.thermal.name,
        batteryTempStartC = before.batteryTempC,
        batteryTempEndC = tempEnd,
        charging = before.charging,
        batteryPercent = percentAfter,
        result = result,
    )
}
