package com.example.learnedge.data.content

import android.os.SystemClock
import android.util.Log
import com.example.learnedge.data.curriculum.CurriculumRepository
import com.example.learnedge.data.db.AppDatabase
import com.example.learnedge.data.db.ContentKind
import com.example.learnedge.data.db.GeneratedContentEntity
import com.example.learnedge.data.learner.LearnerStateRepository
import com.example.learnedge.data.profile.ProfileRepository
import com.example.learnedge.domain.ContentSource
import com.example.learnedge.domain.ForegroundDecision
import com.example.learnedge.domain.LevelBand
import com.example.learnedge.llm.EngineKind
import com.example.learnedge.llm.LlmException
import com.example.learnedge.llm.LlmService
import com.example.learnedge.llm.ModelConfig
import com.example.learnedge.llm.benchmark.GenerationRecorder
import com.example.learnedge.llm.explain.BundledExplanation
import com.example.learnedge.llm.explain.ExplanationGenerator
import com.example.learnedge.llm.explain.ExplanationInput
import com.example.learnedge.llm.explain.ExplanationPrompt
import com.example.learnedge.llm.explain.ExplanationValidation
import com.example.learnedge.llm.explain.ExplanationValidator
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ExplanationStatus {
    /** Nothing is being generated; [Explanation.text] is final. */
    READY,

    /** A generation is queued behind another one on the model. */
    WAITING_FOR_MODEL,

    /** The model is writing; [Explanation.text] is the text streamed so far. */
    WRITING,
}

/** What the lesson screen shows for one topic. */
data class Explanation(
    val topicId: String,
    val text: String,
    val source: ContentSource,
    val band: LevelBand,
    val status: ExplanationStatus,
    val attempt: Int = 0,
    val maxAttempts: Int = MAX_ATTEMPTS,
    /** Why the last AI attempt was rejected, when the bundled text is shown because of it. */
    val lastFailure: String? = null,
)

const val MAX_RETRIES = 2
const val MAX_ATTEMPTS = 1 + MAX_RETRIES

/** What happened to one item of ahead-of-time work. */
enum class PrepResult { SAVED, FAILED_VALIDATION, PREEMPTED, NO_MODEL, ALREADY_READY }

data class PrepOutcome(val result: PrepResult, val tokens: Int, val seconds: Double)

/** Thrown inside a background generation when the learner asks for the model, so the item is dropped at once. */
internal class PreemptedException : CancellationException("a request from the learner needs the model")

/**
 * Serves the explanation of a topic. [getExplanation] order: a valid cached AI explanation for the
 * learner's current level band, else a live streamed generation (the bundled key points are shown
 * until it finishes), else the bundled key points. Generation runs in the app scope, so it continues
 * if the learner leaves the screen, and only one runs at a time because there is one model.
 *
 * @param forceInvalid debug hook: when it returns true the validator rejects every answer, to demo the fallback.
 */
class ContentRepository(
    db: AppDatabase,
    private val curriculum: CurriculumRepository,
    private val profiles: ProfileRepository,
    private val learnerState: LearnerStateRepository,
    private val llm: LlmService,
    private val recorder: GenerationRecorder,
    private val scope: CoroutineScope,
    private val forceInvalid: () -> Boolean = { false },
    /** The thermal policy for requests from the learner (see ThermalGovernor.foreground). */
    private val foregroundPolicy: () -> ForegroundDecision = { ForegroundDecision(preferCachedOrBundled = false, maxOutputChars = 1_500) },
) {
    private val dao = db.generatedContentDao()
    private val lock = Mutex()
    private val triedThisRun = HashSet<String>()

    private data class Live(val key: String, val status: ExplanationStatus, val attempt: Int, val text: String)

    private class Context(val topicId: String, val input: ExplanationInput) {
        val band get() = input.band
        val key get() = "$topicId|${input.band}"
    }

    private val live = MutableStateFlow<Live?>(null)
    private val lastFailures = MutableStateFlow<Map<String, String>>(emptyMap())

    fun getExplanation(topicId: String): Flow<Explanation> = flow {
        val ctx = resolve(topicId) ?: return@flow
        request(ctx, force = false, creative = false)
        val bundled = BundledExplanation.build(ctx.input.topicName, ctx.input.keyPoints)
        emitAll(
            combine(dao.observeLatestVerifiedAi(topicId, ContentKind.EXPLANATION.name, ctx.band.name, ExplanationPrompt.VERSION), live, lastFailures) { cached, liveState, failures ->
                val running = liveState?.takeIf { it.key == ctx.key }
                val failure = failures[ctx.key]
                when {
                    running?.status == ExplanationStatus.WRITING && running.text.isNotBlank() ->
                        Explanation(topicId, running.text, ContentSource.AI, ctx.band, ExplanationStatus.WRITING, running.attempt)
                    running != null ->
                        // Waiting, or between attempts: keep showing what the learner can already read.
                        Explanation(
                            topicId, cached?.payload ?: bundled, if (cached != null) ContentSource.AI else ContentSource.BUNDLED,
                            ctx.band, running.status, running.attempt,
                        )
                    cached != null -> Explanation(topicId, cached.payload, ContentSource.AI, ctx.band, ExplanationStatus.READY)
                    else -> Explanation(topicId, bundled, ContentSource.BUNDLED, ctx.band, ExplanationStatus.READY, lastFailure = failure)
                }
            },
        )
    }

    /** Writes a new explanation of [topicId] at a higher temperature, for "Explain it differently". */
    fun explainDifferently(topicId: String) {
        scope.launch { resolve(topicId)?.let { request(it, force = true, creative = true) } }
    }

    /** The topic, its graph neighbours and the learner's level: everything the prompt is built from. */
    private suspend fun resolve(topicId: String): Context? {
        val profile = profiles.observe().first() ?: return null
        val track = curriculum.track(profile.trackId)
        val graph = track.graph
        val topic = graph.topic(topicId)
        val mastery = learnerState.observe().first()
        val known = graph.getPrerequisites(topicId).filter { mastery[it]?.isMastered == true }.map { graph.topic(it).name }
        return Context(
            topicId,
            ExplanationInput(
                topicId = topicId,
                topicName = topic.name,
                skillName = track.skill(topic.skillId).name,
                trackName = track.name,
                keyPoints = topic.keyPoints,
                knownPrerequisites = known,
                unlocks = graph.getDependents(topicId).map { graph.topic(it).name },
                band = LevelBand.of(mastery[topicId]?.mastery ?: 0.0),
            ),
        )
    }

    /**
     * Starts generation unless one is running or the topic is already covered. Automatic requests
     * try each topic and band at most once per app run; [force] always asks again.
     */
    private suspend fun request(ctx: Context, force: Boolean, creative: Boolean) {
        // Runs inline, so the "waiting" state is set before the lesson screen sees its first value.
        run {
            lock.withLock {
                if (live.value != null) return@withLock
                val cached = dao.latestVerifiedAi(ctx.topicId, ContentKind.EXPLANATION.name, ctx.band.name, ExplanationPrompt.VERSION)
                if (!force && (cached != null || ctx.key in triedThisRun)) return@withLock
                // A hot phone: show the bundled key points instead of starting a new generation (unless the learner asked explicitly).
                if (!force && foregroundPolicy().preferCachedOrBundled) return@withLock
                triedThisRun += ctx.key
                live.value = Live(ctx.key, ExplanationStatus.WAITING_FOR_MODEL, attempt = 0, text = "")
                scope.launch { generate(ctx, creative) }
            }
        }
    }

    private suspend fun generate(ctx: Context, creative: Boolean) {
        try {
            val kind = llm.state.value.kind
            val modelId = if (kind == EngineKind.OnDevice) ModelConfig.ON_DEVICE_MODEL_ID else kind.label
            val maxChars = foregroundPolicy().maxOutputChars
            val model: (suspend (String, Int) -> String)? = if (llm.awaitReady()) {
                { prompt, attempt ->
                    llm.withEngine { engine ->
                        live.value = Live(ctx.key, ExplanationStatus.WRITING, attempt, text = "")
                        val text = StringBuilder()
                        var stopRequested = false
                        val temperature = when {
                            attempt > 1 -> RETRY_TEMPERATURE
                            creative -> DIFFERENT_TEMPERATURE
                            else -> FIRST_TEMPERATURE
                        }
                        recorder.generate(engine, LABEL, attempt, prompt, temperature) { token ->
                            text.append(token)
                            live.update { it?.copy(text = text.toString()) }
                            // A 150-word answer is under ~1,000 characters; far beyond that is a runaway answer.
                            if (!stopRequested && text.length > maxChars) {
                                stopRequested = true
                                engine.stop()
                            }
                        }
                        Log.i(TAG, "${ctx.topicId} attempt $attempt (temperature $temperature) raw output: ${text.take(400)}")
                        text.toString()
                    } ?: throw LlmException("No model is ready")
                }
            } else {
                null
            }
            val validate: (String) -> ExplanationValidation = { raw ->
                if (forceInvalid()) {
                    ExplanationValidation.Invalid("forced validation failure (debug flag file)")
                } else {
                    ExplanationValidator.validate(raw, ctx.input.keyPoints)
                }
            }
            val result = ExplanationGenerator(MAX_RETRIES).generate(ctx.input, model, validate)
            Log.i(TAG, "Explanation ${ctx.topicId} (${ctx.band}): ${result.source}, ${result.modelCalls} model calls, failures=${result.failures}")
            lastFailures.update { map ->
                if (result.source == ContentSource.AI) map - ctx.key else map + (ctx.key to (result.failures.lastOrNull() ?: "no model is ready"))
            }
            if (result.source == ContentSource.AI) saveExplanation(ctx, result.text, modelId)
        } finally {
            live.value = null
        }
    }

    private suspend fun saveExplanation(ctx: Context, text: String, modelId: String) {
        dao.insert(
            GeneratedContentEntity(
                topicId = ctx.topicId,
                kind = ContentKind.EXPLANATION.name,
                payload = text,
                source = ContentSource.AI.name,
                levelBand = ctx.band.name,
                promptVersion = ExplanationPrompt.VERSION,
                modelId = modelId,
                verified = true,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** The (topic, level band) pairs that already have a valid cached explanation written by the current prompt version. */
    suspend fun cachedExplanations(): Set<Pair<String, LevelBand>> =
        dao.verifiedAiRows(ContentKind.EXPLANATION.name, ExplanationPrompt.VERSION).mapTo(HashSet()) { it.topicId to LevelBand.valueOf(it.levelBand) }

    fun observeCachedExplanations(): Flow<List<GeneratedContentEntity>> =
        dao.observeVerifiedAiRows(ContentKind.EXPLANATION.name, ExplanationPrompt.VERSION)

    /**
     * Writes and stores one explanation ahead of time (Night Shift, "Prepare now"): generate, validate (two
     * retries), save. It does not touch the lesson screen's live state, so it never blocks a learner's own
     * request. A [background] item gives way at once when the learner asks for the model: the output stops
     * and the result is [PrepResult.PREEMPTED].
     *
     * @param maxChars cap on the characters of one generation (the thermal policy lowers it on a hot phone).
     */
    suspend fun prepareExplanation(topicId: String, background: Boolean, maxChars: Int): PrepOutcome {
        val ctx = resolve(topicId) ?: return PrepOutcome(PrepResult.NO_MODEL, 0, 0.0)
        if (dao.latestVerifiedAi(ctx.topicId, ContentKind.EXPLANATION.name, ctx.band.name, ExplanationPrompt.VERSION) != null) {
            return PrepOutcome(PrepResult.ALREADY_READY, 0, 0.0)
        }
        val kind = llm.state.value.kind
        val modelId = if (kind == EngineKind.OnDevice) ModelConfig.ON_DEVICE_MODEL_ID else kind.label
        val startedAt = SystemClock.elapsedRealtime()
        var tokens = 0
        fun seconds() = (SystemClock.elapsedRealtime() - startedAt) / 1000.0

        val model: (suspend (String, Int) -> String)? = if (llm.awaitReady()) {
            { prompt, attempt ->
                llm.withEngine(background) { engine ->
                    val text = StringBuilder()
                    var stopRequested = false
                    val measured = recorder.generate(engine, LABEL_PREPARE, attempt, prompt, if (attempt > 1) RETRY_TEMPERATURE else FIRST_TEMPERATURE) { token ->
                        text.append(token)
                        if (!stopRequested && (text.length > maxChars || (background && llm.foregroundPending))) {
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
        val validate: (String) -> ExplanationValidation = { raw ->
            if (forceInvalid()) ExplanationValidation.Invalid("forced validation failure (debug flag file)") else ExplanationValidator.validate(raw, ctx.input.keyPoints)
        }
        val result = try {
            ExplanationGenerator(MAX_RETRIES).generate(ctx.input, model, validate)
        } catch (e: PreemptedException) {
            return PrepOutcome(PrepResult.PREEMPTED, tokens, seconds())
        }
        Log.i(TAG, "Prepared ${ctx.topicId} (${ctx.band}): ${result.source}, ${result.modelCalls} model calls, failures=${result.failures}")
        return when {
            result.source == ContentSource.AI -> {
                saveExplanation(ctx, result.text, modelId)
                PrepOutcome(PrepResult.SAVED, tokens, seconds())
            }
            result.modelCalls == 0 -> PrepOutcome(PrepResult.NO_MODEL, tokens, seconds())
            else -> PrepOutcome(PrepResult.FAILED_VALIDATION, tokens, seconds())
        }
    }

    private companion object {
        /** Prompt label of explanation generations in benchmarks.csv; the run column is the attempt (1-3). */
        const val LABEL = "explain"
        /** Prompt label of explanations prepared ahead of time. */
        const val LABEL_PREPARE = "prepare"
        const val FIRST_TEMPERATURE = 0.6f
        /** Retries are less random: a looping or off-topic answer is more likely at high temperature. */
        const val RETRY_TEMPERATURE = 0.3f
        const val DIFFERENT_TEMPERATURE = 0.9f
        const val TAG = "LearnEdgeContent"
    }
}
