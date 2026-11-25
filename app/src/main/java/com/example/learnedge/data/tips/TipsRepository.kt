package com.example.learnedge.data.tips

import android.os.SystemClock
import android.util.Log
import com.example.learnedge.data.content.PrepOutcome
import com.example.learnedge.data.content.PrepResult
import com.example.learnedge.data.content.PreemptedException
import com.example.learnedge.data.db.AppDatabase
import com.example.learnedge.data.db.DailyTipEntity
import com.example.learnedge.llm.LlmException
import com.example.learnedge.llm.LlmService
import com.example.learnedge.llm.benchmark.GenerationRecorder
import com.example.learnedge.llm.tips.DailyTips
import com.example.learnedge.llm.tips.JsonCompletionDetector
import com.example.learnedge.llm.tips.Tip
import com.example.learnedge.llm.tips.TipGenerator
import com.example.learnedge.llm.tips.TipInput
import com.example.learnedge.llm.tips.TipSource
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StoredTips(val tips: DailyTips, val signature: String)

/** Live state of a background tip generation, for the Today screen. */
data class TipsProgress(
    val running: Boolean = false,
    val waitingForModel: Boolean = false,
    val attempt: Int = 0,
    val maxAttempts: Int = 1 + MAX_RETRIES,
    val streamedChars: Int = 0,
)

private const val MAX_RETRIES = 2

/**
 * Writes the day's summary and tips with the model in the background, stores them in Room and
 * reuses them until the day's topics change. Each model call is logged to the benchmark CSV.
 */
class TipsRepository(
    db: AppDatabase,
    private val llm: LlmService,
    private val recorder: GenerationRecorder,
    private val scope: CoroutineScope,
) {
    private val dao = db.tipDao()
    private val lock = Mutex()
    private var job: Job? = null
    private var jobSignature: String? = null

    private val _progress = MutableStateFlow(TipsProgress())
    val progress: StateFlow<TipsProgress> = _progress.asStateFlow()

    fun observe(day: LocalDate): Flow<StoredTips?> = dao.observeDay(day.toEpochDay()).map { it.toStoredTips() }

    /** Starts generation for [input] unless tips for exactly these topics exist (or [force] is set). */
    fun ensure(input: TipInput, force: Boolean = false) {
        scope.launch {
            lock.withLock {
                if (!force) {
                    if (jobSignature == input.signature && job?.isActive == true) return@withLock
                    val stored = dao.getDay(input.date.toEpochDay())
                    if (stored.isNotEmpty() && stored.first().signature == input.signature) return@withLock
                }
                job?.cancel()
                jobSignature = input.signature
                job = scope.launch { generate(input) }
            }
        }
    }

    private suspend fun generate(input: TipInput) {
        _progress.value = TipsProgress(running = true, waitingForModel = true)
        try {
            val model: (suspend (String, Int) -> String)? = if (llm.awaitReady()) {
                { prompt, attempt ->
                    llm.withEngine { engine ->
                        _progress.value = TipsProgress(running = true, attempt = attempt)
                        val text = StringBuilder()
                        val json = JsonCompletionDetector()
                        var stopRequested = false
                        recorder.generate(engine, TIPS_LABEL, attempt, prompt) { token ->
                            text.append(token)
                            _progress.update { it.copy(streamedChars = text.length) }
                            // Stop as soon as the JSON object is closed, or if the model rambles on.
                            if (!stopRequested && (json.feed(token) || text.length > MAX_OUTPUT_CHARS)) {
                                stopRequested = true
                                engine.stop()
                            }
                        }
                        Log.i(TAG, "Attempt $attempt raw output: ${text.take(800)}")
                        text.toString()
                    } ?: throw LlmException("No model is ready")
                }
            } else {
                null
            }
            val result = TipGenerator(MAX_RETRIES).generate(input, model)
            Log.i(TAG, "Tips: summary ${result.tips.summarySource}, ${result.modelCalls} model calls, failures=${result.failures}")
            dao.replaceDay(input.date.toEpochDay(), result.tips.toRows(input))
        } finally {
            _progress.value = TipsProgress()
        }
    }

    /** True if valid tips are stored for exactly this input (same day, same topics and minutes). */
    suspend fun isStored(input: TipInput): Boolean = dao.getDay(input.date.toEpochDay()).firstOrNull()?.signature == input.signature

    /**
     * Writes and stores a day's tips ahead of time (Night Shift, "Prepare now") without touching the live
     * progress shown on Today. A [background] item gives way at once when the learner asks for the model.
     */
    suspend fun prepare(input: TipInput, background: Boolean, maxChars: Int): PrepOutcome {
        if (isStored(input)) return PrepOutcome(PrepResult.ALREADY_READY, 0, 0.0)
        val startedAt = SystemClock.elapsedRealtime()
        var tokens = 0
        fun seconds() = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        val model: (suspend (String, Int) -> String)? = if (llm.awaitReady()) {
            { prompt, attempt ->
                llm.withEngine(background) { engine ->
                    val text = StringBuilder()
                    val json = JsonCompletionDetector()
                    var stopRequested = false
                    val measured = recorder.generate(engine, TIPS_LABEL, attempt, prompt) { token ->
                        text.append(token)
                        if (!stopRequested && (json.feed(token) || text.length > maxChars * 2 || (background && llm.foregroundPending))) {
                            stopRequested = true
                            engine.stop()
                        }
                    }
                    tokens += measured.stats.tokenCount
                    if (background && llm.foregroundPending) throw PreemptedException()
                    text.toString()
                } ?: throw LlmException("No model is ready")
            }
        } else {
            null
        }
        val result = try {
            TipGenerator(MAX_RETRIES).generate(input, model)
        } catch (e: PreemptedException) {
            return PrepOutcome(PrepResult.PREEMPTED, tokens, seconds())
        }
        return when {
            result.tips.summarySource == TipSource.AI -> {
                dao.replaceDay(input.date.toEpochDay(), result.tips.toRows(input))
                PrepOutcome(PrepResult.SAVED, tokens, seconds())
            }
            result.modelCalls == 0 -> PrepOutcome(PrepResult.NO_MODEL, tokens, seconds())
            else -> PrepOutcome(PrepResult.FAILED_VALIDATION, tokens, seconds())
        }
    }

    private fun DailyTips.toRows(input: TipInput): List<DailyTipEntity> {
        val now = System.currentTimeMillis()
        val day = input.date.toEpochDay()
        return listOf(DailyTipEntity(day, SUMMARY_ID, summary, summarySource.name, input.signature, now)) +
            tips.map { (topicId, tip) -> DailyTipEntity(day, topicId, tip.text, tip.source.name, input.signature, now) }
    }

    private fun List<DailyTipEntity>.toStoredTips(): StoredTips? {
        val summary = firstOrNull { it.topicId == SUMMARY_ID } ?: return null
        return StoredTips(
            tips = DailyTips(
                summary = summary.text,
                summarySource = TipSource.valueOf(summary.source),
                tips = filter { it.topicId != SUMMARY_ID }.associate { it.topicId to Tip(it.text, TipSource.valueOf(it.source)) },
            ),
            signature = summary.signature,
        )
    }

    private companion object {
        const val SUMMARY_ID = ""
        /** Prompt label of tip generations in benchmarks.csv; the run column is the attempt (1–3). */
        const val TIPS_LABEL = "tips"
        /** Generous for a few topics (each tip ≤ 300 chars); anything longer is a runaway answer. */
        const val MAX_OUTPUT_CHARS = 3_000
        const val TAG = "LearnEdgeTips"
    }
}
