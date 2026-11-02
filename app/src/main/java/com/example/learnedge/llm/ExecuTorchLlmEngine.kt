package com.example.learnedge.llm

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule
import org.pytorch.executorch.extension.llm.LlmModuleConfig

/** Runs a Llama .pte model on-device with the ExecuTorch 1.5.1 Android LLM API. */
class ExecuTorchLlmEngine(private val files: ModelFiles) : LlmEngine {
    override val name = "On-device"
    override var loadTimeMs: Long? = null
        private set

    // LlmModule calls are blocking, so they all run on one dedicated background thread.
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "llm-worker") }
    private val dispatcher = executor.asCoroutineDispatcher()

    @Volatile private var module: LlmModule? = null

    override suspend fun load() = withContext(dispatcher) {
        if (module != null) return@withContext
        val start = SystemClock.elapsedRealtime()
        val newModule = try {
            LlmModule(
                LlmModuleConfig.create()
                    .modulePath(files.model.absolutePath)
                    .tokenizerPath(files.tokenizer.absolutePath)
                    .temperature(ModelConfig.TEMPERATURE)
                    .modelType(LlmModuleConfig.MODEL_TYPE_TEXT)
                    .build()
            )
        } catch (e: Exception) {
            throw LlmException("Could not open the model files: ${e.message}\n\n${describeFiles()}", e)
        } catch (e: UnsatisfiedLinkError) {
            throw LlmException("The ExecuTorch native library could not be loaded on this phone: ${e.message}", e)
        }
        try {
            newModule.load()
        } catch (e: Exception) {
            newModule.close()
            throw LlmException(
                "ExecuTorch failed to load the model: ${e.message ?: e.javaClass.simpleName}\n\n" +
                    "${describeFiles()}\n\nThe .pte may be incomplete, or exported for an incompatible " +
                    "ExecuTorch version, or the tokenizer may not match the model.",
                e,
            )
        }
        module = newModule
        loadTimeMs = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "Model loaded in $loadTimeMs ms from ${files.model}")
    }

    override suspend fun generate(prompt: String, onToken: (String) -> Unit): GenerationStats =
        withContext(dispatcher) {
            val m = module ?: throw LlmException("Model is not loaded")
            // Each prompt carries its own history, so start from an empty KV cache.
            m.resetContext()

            val tracker = StatsTracker()
            var nativeError: String? = null
            val config = LlmGenerationConfig.create()
                .seqLen(ModelConfig.SEQ_LEN)
                .temperature(ModelConfig.TEMPERATURE)
                .echo(false)
                .build()
            try {
                m.generate(prompt, config, object : LlmCallback {
                    override fun onResult(result: String) {
                        val text = PromptFormat.clean(result)
                        tracker.onToken()
                        if (text.isNotEmpty()) onToken(text)
                    }

                    override fun onStats(stats: String) {
                        Log.i(TAG, "Native stats: $stats")
                    }

                    override fun onError(errorCode: Int, message: String) {
                        nativeError = "error $errorCode: $message"
                    }
                })
            } catch (e: Exception) {
                throw LlmException("Generation failed: ${e.message}", e)
            }
            nativeError?.let { throw LlmException("Generation failed ($it)") }
            tracker.finish().also { Log.i(TAG, "Generation stats: $it") }
        }

    override fun stop() {
        module?.stop()
    }

    override fun close() {
        stop()
        // Runs after any in-flight generate() returns, since the executor is single-threaded.
        executor.execute {
            module?.close()
            module = null
        }
        executor.shutdown()
    }

    private fun describeFiles(): String =
        "• ${files.model.absolutePath}: ${ModelConfig.describe(files.model)}\n" +
            "• ${files.tokenizer.absolutePath}: ${ModelConfig.describe(files.tokenizer)}"

    private companion object {
        const val TAG = "LearnEdgeLlm"
    }
}
