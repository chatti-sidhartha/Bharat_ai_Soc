package com.example.learnedge.llm

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

data class EngineState(val kind: EngineKind, val status: EngineStatus, val loadTimeMs: Long?)

/** The on-device model is the default: everything must work in airplane mode. */
val DEFAULT_ENGINE = EngineKind.OnDevice

/**
 * Owns the one loaded [LlmEngine] for the whole app (chat, daily tips and benchmarks), so a 1 GB
 * model is loaded once. [withEngine] serialises generations: a model can only run one at a time.
 */
class LlmService(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(EngineState(DEFAULT_ENGINE, EngineStatus.Loading, null))
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile private var engine: LlmEngine? = null
    private val generationLock = Mutex()

    init {
        select(DEFAULT_ENGINE)
    }

    fun select(kind: EngineKind) {
        engine?.close()
        engine = null
        _state.value = EngineState(kind, EngineStatus.Loading, null)

        val newEngine = when (kind) {
            EngineKind.Ollama -> OllamaLlmEngine()
            EngineKind.Mock -> MockLlmEngine()
            EngineKind.OnDevice -> {
                val files = ModelConfig.findModelFiles(context)
                if (files == null) {
                    _state.value = EngineState(kind, EngineStatus.Failed(ModelConfig.missingFilesMessage(context)), null)
                    return
                }
                ExecuTorchLlmEngine(files)
            }
        }
        engine = newEngine
        scope.launch {
            val status = try {
                newEngine.load()
                EngineStatus.Ready
            } catch (e: LlmException) {
                EngineStatus.Failed(e.message ?: "Unknown error")
            }
            // Ignore the result if another engine was selected while this one was loading.
            if (engine === newEngine) _state.update { it.copy(status = status, loadTimeMs = newEngine.loadTimeMs) }
        }
    }

    /** Waits until loading has finished; true if an engine is ready to generate. */
    suspend fun awaitReady(): Boolean = state.first { it.status != EngineStatus.Loading }.status == EngineStatus.Ready

    private val foregroundWaiters = AtomicInteger(0)
    @Volatile private var backgroundRunning = false

    /** True while a request from the learner is waiting for (or using) the model: background work must stop at once. */
    val foregroundPending: Boolean get() = foregroundWaiters.get() > 0

    /**
     * Runs [block] with exclusive use of the ready engine. Returns null when no engine is ready.
     * Other callers wait, so chat, tips and benchmarks never generate at the same time.
     *
     * A request with [background] = false comes from the learner. It stops a background generation that
     * is running right now, so the learner never waits for Night Shift; background blocks must also
     * check [foregroundPending] while they stream and end their output early.
     */
    suspend fun <T> withEngine(background: Boolean = false, block: suspend (LlmEngine) -> T): T? {
        if (!awaitReady()) return null
        if (!background) {
            foregroundWaiters.incrementAndGet()
            if (backgroundRunning) engine?.stop()
        }
        try {
            return generationLock.withLock {
                val current = engine?.takeIf { state.value.status == EngineStatus.Ready } ?: return@withLock null
                if (background) backgroundRunning = true
                try {
                    block(current)
                } finally {
                    if (background) backgroundRunning = false
                }
            }
        } finally {
            if (!background) foregroundWaiters.decrementAndGet()
        }
    }

    /** Asks the running generation, if any, to stop early. */
    fun stop() {
        engine?.stop()
    }
}
