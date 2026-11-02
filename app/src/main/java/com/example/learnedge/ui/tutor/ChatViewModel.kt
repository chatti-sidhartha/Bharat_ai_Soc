package com.example.learnedge.ui.tutor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.learnedge.llm.benchmark.BenchmarkLog
import com.example.learnedge.llm.benchmark.BenchmarkPrompts
import com.example.learnedge.llm.benchmark.BenchmarkRecord
import com.example.learnedge.llm.benchmark.BenchmarkSummary
import com.example.learnedge.llm.benchmark.GenerationRecorder
import com.example.learnedge.llm.benchmark.PromptSummary
import com.example.learnedge.llm.ExecuTorchLlmEngine
import com.example.learnedge.llm.GenerationStats
import com.example.learnedge.llm.LlmEngine
import com.example.learnedge.llm.LlmException
import com.example.learnedge.llm.MockLlmEngine
import com.example.learnedge.llm.ModelConfig
import com.example.learnedge.llm.OllamaLlmEngine
import com.example.learnedge.llm.PromptFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EngineKind(val label: String) {
    Ollama("Ollama"),
    OnDevice("On-device"),
    Mock("Mock"),
}

sealed interface EngineStatus {
    data object Loading : EngineStatus
    data object Ready : EngineStatus
    data class Failed(val message: String) : EngineStatus
}

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

/** Ollama on the dev PC is the default local LLM; the other engines stay available from the selector. */
val DEFAULT_ENGINE = EngineKind.Ollama

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var engine: LlmEngine? = null
    private var nextId = 0L

    private val benchmarkLog = BenchmarkLog(application)
    private val recorder = GenerationRecorder(application, benchmarkLog)
    private var benchmarkJob: Job? = null

    init {
        selectEngine(DEFAULT_ENGINE)
    }

    fun selectEngine(kind: EngineKind) {
        if (_state.value.isGenerating) return
        engine?.close()
        engine = null
        _state.update { it.copy(engineKind = kind, engineStatus = EngineStatus.Loading, loadTimeMs = null) }

        val newEngine = when (kind) {
            EngineKind.Ollama -> OllamaLlmEngine()
            EngineKind.Mock -> MockLlmEngine()
            EngineKind.OnDevice -> {
                val files = ModelConfig.findModelFiles(getApplication())
                if (files == null) {
                    val message = ModelConfig.missingFilesMessage(getApplication())
                    _state.update { it.copy(engineStatus = EngineStatus.Failed(message)) }
                    return
                }
                ExecuTorchLlmEngine(files)
            }
        }
        engine = newEngine
        viewModelScope.launch {
            try {
                newEngine.load()
                _state.update { it.copy(engineStatus = EngineStatus.Ready, loadTimeMs = newEngine.loadTimeMs) }
            } catch (e: LlmException) {
                _state.update { it.copy(engineStatus = EngineStatus.Failed(e.message ?: "Unknown error")) }
            }
        }
    }

    fun send(text: String) {
        val current = _state.value
        val activeEngine = engine
        if (text.isBlank() || current.isGenerating || current.engineStatus != EngineStatus.Ready || activeEngine == null) return

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
                val result = recorder.generate(activeEngine, BenchmarkRecord.CHAT_LABEL, run = 0, prompt) { token ->
                    updateMessage(replyId) { it.copy(text = it.text + token) }
                }
                updateMessage(replyId) { it.copy(text = it.text.trim(), stats = result.stats) }
            } catch (e: LlmException) {
                updateMessage(replyId) { it.copy(error = e.message ?: "Unknown error") }
            } finally {
                _state.update { it.copy(isGenerating = false) }
            }
        }
    }

    fun stop() {
        engine?.stop()
    }

    /** Runs every benchmark prompt [BenchmarkPrompts.RUNS_PER_PROMPT] times on the current engine. */
    fun runBenchmark() {
        val current = _state.value
        val activeEngine = engine
        if (current.isGenerating || current.engineStatus != EngineStatus.Ready || activeEngine == null) return

        val schedule = BenchmarkPrompts.schedule()
        _state.update {
            it.copy(
                isGenerating = true,
                benchmark = BenchmarkUiState(
                    engineLabel = activeEngine.name,
                    totalRuns = schedule.size,
                    csvPath = benchmarkLog.file.absolutePath,
                ),
            )
        }
        benchmarkJob = viewModelScope.launch {
            val records = mutableListOf<BenchmarkRecord>()
            try {
                for ((prompt, run) in schedule) {
                    updateBenchmark { it.copy(currentRun = "${prompt.label}, run $run of ${BenchmarkPrompts.RUNS_PER_PROMPT}") }
                    // No chat history, so every run of a prompt processes exactly the same input.
                    val formatted = PromptFormat.llama3(SYSTEM_PROMPT, emptyList(), prompt.text)
                    records += recorder.generate(activeEngine, prompt.label, run, formatted) {}.record
                    updateBenchmark {
                        it.copy(completedRuns = records.size, summaries = BenchmarkSummary.summarize(records))
                    }
                }
                benchmarkLog.logSummary(BenchmarkSummary.formatTable(activeEngine.name, BenchmarkSummary.summarize(records)))
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
        engine?.stop()
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

    override fun onCleared() {
        engine?.close()
    }

    private companion object {
        const val MAX_HISTORY_MESSAGES = 6
        const val SYSTEM_PROMPT =
            "You are LearnEdge, a friendly and concise learning coach. " +
                "Give short, practical answers."
    }
}
