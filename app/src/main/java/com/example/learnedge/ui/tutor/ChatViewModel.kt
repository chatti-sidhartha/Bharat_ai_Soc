package com.example.learnedge.ui.tutor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.learnedge.appContainer
import com.example.learnedge.llm.DEFAULT_ENGINE
import com.example.learnedge.llm.EngineKind
import com.example.learnedge.llm.EngineStatus
import com.example.learnedge.llm.GenerationStats
import com.example.learnedge.llm.LlmException
import com.example.learnedge.llm.PromptFormat
import com.example.learnedge.llm.benchmark.BenchmarkPrompts
import com.example.learnedge.llm.benchmark.BenchmarkRecord
import com.example.learnedge.llm.benchmark.BenchmarkSummary
import com.example.learnedge.llm.benchmark.PromptSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val stats: GenerationStats? = null,
    val error: String? = null,
)

/** Progress and results of a benchmark run. Shown in a dialog while non-null. */
data class BenchmarkUiState(
    val engineLabel: String,
    val totalRuns: Int,
    val csvPath: String,
    val running: Boolean = true,
    val completedRuns: Int = 0,
    val currentRun: String? = null,
    val summaries: List<PromptSummary> = emptyList(),
    val error: String? = null,
)

data class ChatUiState(
    val engineKind: EngineKind = DEFAULT_ENGINE,
    val engineStatus: EngineStatus = EngineStatus.Loading,
    val loadTimeMs: Long? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val benchmark: BenchmarkUiState? = null,
)

/** Chat with the shared engine from [com.example.learnedge.llm.LlmService]. */
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer
    private val llm = container.llm
    private val _state = MutableStateFlow(ChatUiState())

    val state: StateFlow<ChatUiState> = combine(_state, llm.state) { chat, engine ->
        chat.copy(engineKind = engine.kind, engineStatus = engine.status, loadTimeMs = engine.loadTimeMs)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    private var nextId = 0L
    private var benchmarkJob: Job? = null

    fun selectEngine(kind: EngineKind) {
        if (_state.value.isGenerating) return
        llm.select(kind)
    }

    fun send(text: String) {
        val current = state.value
        if (text.isBlank() || current.isGenerating || current.engineStatus != EngineStatus.Ready) return

        val history = current.messages
            .filter { it.error == null && it.text.isNotBlank() }
            .takeLast(MAX_HISTORY_MESSAGES)
            .map { PromptFormat.Turn(it.fromUser, it.text) }
        val prompt = PromptFormat.llama3(SYSTEM_PROMPT, history, text)

        val replyId = nextId + 1
        _state.update {
            it.copy(
                messages = it.messages +
                    ChatMessage(nextId, fromUser = true, text = text.trim()) +
                    ChatMessage(replyId, fromUser = false, text = ""),
                isGenerating = true,
            )
        }
        nextId += 2

        viewModelScope.launch {
            try {
                // Waits if today's tips are being generated; the model runs one prompt at a time.
                val result = llm.withEngine { engine ->
                    container.recorder.generate(engine, BenchmarkRecord.CHAT_LABEL, run = 0, prompt) { token ->
                        updateMessage(replyId) { it.copy(text = it.text + token) }
                    }
                } ?: throw LlmException("No model is ready")
                updateMessage(replyId) { it.copy(text = it.text.trim(), stats = result.stats) }
            } catch (e: LlmException) {
                updateMessage(replyId) { it.copy(error = e.message ?: "Unknown error") }
            } finally {
                _state.update { it.copy(isGenerating = false) }
            }
        }
    }

    fun stop() {
        llm.stop()
    }

    /** Runs every benchmark prompt [BenchmarkPrompts.RUNS_PER_PROMPT] times on the current engine. */
    fun runBenchmark() {
        val current = state.value
        if (current.isGenerating || current.engineStatus != EngineStatus.Ready) return

        val schedule = BenchmarkPrompts.schedule()
        _state.update {
            it.copy(
                isGenerating = true,
                benchmark = BenchmarkUiState(
                    engineLabel = current.engineKind.label,
                    totalRuns = schedule.size,
                    csvPath = container.benchmarkLog.file.absolutePath,
                ),
            )
        }
        benchmarkJob = viewModelScope.launch {
            try {
                val records = llm.withEngine { engine ->
                    val records = mutableListOf<BenchmarkRecord>()
                    for ((prompt, run) in schedule) {
                        updateBenchmark { it.copy(currentRun = "${prompt.label}, run $run of ${BenchmarkPrompts.RUNS_PER_PROMPT}") }
                        // No chat history, so every run of a prompt processes exactly the same input.
                        val formatted = PromptFormat.llama3(SYSTEM_PROMPT, emptyList(), prompt.text)
                        records += container.recorder.generate(engine, prompt.label, run, formatted) {}.record
                        updateBenchmark {
                            it.copy(completedRuns = records.size, summaries = BenchmarkSummary.summarize(records))
                        }
                    }
                    records
                } ?: throw LlmException("No model is ready")
                container.benchmarkLog.logSummary(BenchmarkSummary.formatTable(current.engineKind.label, BenchmarkSummary.summarize(records)))
            } catch (e: LlmException) {
                updateBenchmark { it.copy(error = e.message ?: "Unknown error") }
            } finally {
                updateBenchmark { it.copy(running = false, currentRun = null) }
                _state.update { it.copy(isGenerating = false) }
            }
        }
    }

    fun stopBenchmark() {
        if (_state.value.benchmark?.running != true) return
        updateBenchmark { it.copy(error = "Stopped before all runs finished.") }
        benchmarkJob?.cancel()
        // Cancelling doesn't interrupt a blocking native call; this makes the current generation return.
        llm.stop()
    }

    fun dismissBenchmark() {
        if (_state.value.benchmark?.running == true) return
        _state.update { it.copy(benchmark = null) }
    }

    private fun updateMessage(id: Long, change: (ChatMessage) -> ChatMessage) {
        _state.update { state ->
            state.copy(messages = state.messages.map { if (it.id == id) change(it) else it })
        }
    }

    private fun updateBenchmark(change: (BenchmarkUiState) -> BenchmarkUiState) {
        _state.update { state -> state.copy(benchmark = state.benchmark?.let(change)) }
    }

    private companion object {
        const val MAX_HISTORY_MESSAGES = 6
        const val SYSTEM_PROMPT =
            "You are LearnEdge, a friendly and concise learning coach. " +
                "Give short, practical answers."
    }
}
