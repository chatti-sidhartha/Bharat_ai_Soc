package com.example.learnedge.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.learnedge.llm.ExecuTorchLlmEngine
import com.example.learnedge.llm.GenerationStats
import com.example.learnedge.llm.LlmEngine
import com.example.learnedge.llm.LlmException
import com.example.learnedge.llm.MockLlmEngine
import com.example.learnedge.llm.ModelConfig
import com.example.learnedge.llm.OllamaLlmEngine
import com.example.learnedge.llm.PromptFormat
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

data class ChatUiState(
    val engineKind: EngineKind = DEFAULT_ENGINE,
    val engineStatus: EngineStatus = EngineStatus.Loading,
    val loadTimeMs: Long? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
)

/** Ollama on the dev PC is the default local LLM; the other engines stay available from the selector. */
val DEFAULT_ENGINE = EngineKind.Ollama

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var engine: LlmEngine? = null
    private var nextId = 0L

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
                val stats = activeEngine.generate(prompt) { token ->
                    updateMessage(replyId) { it.copy(text = it.text + token) }
                }
                updateMessage(replyId) { it.copy(text = it.text.trim(), stats = stats) }
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

    private fun updateMessage(id: Long, change: (ChatMessage) -> ChatMessage) {
        _state.update { state ->
            state.copy(messages = state.messages.map { if (it.id == id) change(it) else it })
        }
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
