package com.example.learnedge.data.lessons

import android.util.Log
import com.example.learnedge.data.db.AppDatabase
import com.example.learnedge.data.db.TopicLessonEntity
import com.example.learnedge.llm.LlmException
import com.example.learnedge.llm.LlmService
import com.example.learnedge.llm.benchmark.GenerationRecorder
import com.example.learnedge.llm.lessons.Lesson
import com.example.learnedge.llm.lessons.LessonGenerator
import com.example.learnedge.llm.lessons.LessonInput
import com.example.learnedge.llm.lessons.LessonPromptBuilder
import com.example.learnedge.llm.lessons.LessonSource
import com.example.learnedge.llm.tips.JsonCompletionDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Live state of a background lesson generation, for the lesson screen. */
data class LessonProgress(
    /** The topic being written, or null when idle. */
    val topicId: String? = null,
    val waitingForModel: Boolean = false,
    val attempt: Int = 0,
    val maxAttempts: Int = 1 + MAX_RETRIES,
    val streamedChars: Int = 0,
) {
    val running: Boolean get() = topicId != null
}

private const val MAX_RETRIES = 2

/**
 * Writes one lesson per topic with the model in the background and stores the ones that pass
 * validation. Topics whose attempts all fail are not stored: the screen shows the bundled text and
 * only asks the model again when the learner presses the button, so it never loops.
 */
class LessonRepository(
    db: AppDatabase,
    private val llm: LlmService,
    private val recorder: GenerationRecorder,
    private val scope: CoroutineScope,
) {
    private val dao = db.lessonDao()
    private val lock = Mutex()
    private val triedThisRun = HashSet<String>()

    private val _progress = MutableStateFlow(LessonProgress())
    val progress: StateFlow<LessonProgress> = _progress.asStateFlow()

    /** The stored AI lesson for [topicId], or null if none was written yet. */
    fun observe(topicId: String): Flow<Lesson?> =
        dao.observe(topicId).map { row -> row?.let { Lesson(it.explanation, it.example, LessonSource.AI) } }

    /**
     * Starts writing the lesson unless one is stored. Automatic calls try each topic at most once
     * per app run; [force] asks the model again. Only one lesson is written at a time.
     */
    fun ensure(input: LessonInput, force: Boolean = false) {
        scope.launch {
            lock.withLock {
                if (_progress.value.running) return@withLock
                if (!force && (dao.get(input.topicId) != null || input.topicId in triedThisRun)) return@withLock
                triedThisRun += input.topicId
                _progress.value = LessonProgress(topicId = input.topicId, waitingForModel = true)
                scope.launch { generate(input) }
            }
        }
    }

    private suspend fun generate(input: LessonInput) {
        try {
            val model: (suspend (String, Int) -> String)? = if (llm.awaitReady()) {
                { prompt, attempt ->
                    llm.withEngine { engine ->
                        _progress.value = LessonProgress(topicId = input.topicId, attempt = attempt)
                        val text = StringBuilder()
                        val json = JsonCompletionDetector(LessonPromptBuilder.assistantPrefix(input.topicId))
                        var stopRequested = false
                        recorder.generate(engine, LESSON_LABEL, attempt, prompt) { token ->
                            text.append(token)
                            _progress.update { it.copy(streamedChars = text.length) }
                            // Stop as soon as the JSON object is closed, or if the model rambles on.
                            if (!stopRequested && (json.feed(token) || text.length > MAX_OUTPUT_CHARS)) {
                                stopRequested = true
                                engine.stop()
                            }
                        }
                        Log.i(TAG, "${input.topicId} attempt $attempt raw output: ${text.take(600)}")
                        text.toString()
                    } ?: throw LlmException("No model is ready")
                }
            } else {
                null
            }
            val result = LessonGenerator(MAX_RETRIES).generate(input, model)
            Log.i(TAG, "Lesson ${input.topicId}: ${result.lesson.source}, ${result.modelCalls} model calls, failures=${result.failures}")
            if (result.lesson.source == LessonSource.AI) {
                dao.upsert(
                    TopicLessonEntity(input.topicId, result.lesson.explanation, result.lesson.example.orEmpty(), System.currentTimeMillis()),
                )
            }
        } finally {
            _progress.value = LessonProgress()
        }
    }

    private companion object {
        /** Prompt label of lesson generations in benchmarks.csv; the run column is the attempt (1-3). */
        const val LESSON_LABEL = "lesson"
        /** A lesson is at most ~1,200 characters of JSON; anything much longer is a runaway answer. */
        const val MAX_OUTPUT_CHARS = 2_000
        const val TAG = "LearnEdgeLessons"
    }
}
