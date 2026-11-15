package com.example.learnedge.llm

import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Streams completions from an Ollama server on the development PC.
 * Uses /api/generate with raw=true because prompts are already in the Llama 3 chat template.
 */
class OllamaLlmEngine(
    private val baseUrl: String = OllamaConfig.BASE_URL,
    private val model: String = OllamaConfig.MODEL,
) : LlmEngine {
    override val name = "Ollama"
    override var loadTimeMs: Long? = null
        private set

    @Volatile private var activeConnection: HttpURLConnection? = null
    @Volatile private var stopRequested = false

    override suspend fun load() = withContext(Dispatchers.IO) {
        if (loadTimeMs != null) return@withContext
        val tags = try {
            request("GET", "/api/tags", body = null, readTimeoutMs = 10_000) { it.readText() }
        } catch (e: IOException) {
            throw LlmException(
                "Can't reach Ollama at $baseUrl (${e.message}).\n" +
                    "Is `ollama serve` running on the PC and did you run `adb reverse tcp:11434 tcp:11434`?", e
            )
        }
        val installed = JSONObject(tags).optJSONArray("models")
            ?.let { arr -> List(arr.length()) { arr.getJSONObject(it).optString("name") } }
            .orEmpty()
        if (installed.none { it == model || it == "$model:latest" }) {
            throw LlmException(
                "Ollama model '$model' is not installed. Run `ollama pull $model` on the PC.\n" +
                    "Installed: ${installed.ifEmpty { listOf("none") }.joinToString()}"
            )
        }
        // An empty prompt makes Ollama load the model into memory, so the first chat isn't slow.
        val start = SystemClock.elapsedRealtime()
        // Same num_ctx as generate(); a different value would make Ollama reload the model on the first message.
        val warmUp = JSONObject().put("model", model).put("prompt", "").put("keep_alive", OllamaConfig.KEEP_ALIVE)
            .put("options", JSONObject().put("num_ctx", ModelConfig.SEQ_LEN))
        try {
            request("POST", "/api/generate", warmUp.toString(), readTimeoutMs = 300_000) { it.readText() }
        } catch (e: IOException) {
            throw LlmException("Ollama failed to load '$model': ${e.message}", e)
        }
        loadTimeMs = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "Ollama model $model ready in $loadTimeMs ms")
    }

    override suspend fun generate(prompt: String, temperature: Float, onToken: (String) -> Unit): GenerationStats =
        withContext(Dispatchers.IO) {
            stopRequested = false
            val tracker = StatsTracker()
            val body = JSONObject()
                .put("model", model)
                .put("prompt", prompt)
                .put("raw", true)
                .put("stream", true)
                .put("keep_alive", OllamaConfig.KEEP_ALIVE)
                .put("options", JSONObject()
                    .put("temperature", temperature.toDouble())
                    .put("num_ctx", ModelConfig.SEQ_LEN))
            try {
                request("POST", "/api/generate", body.toString(), readTimeoutMs = 300_000) { stream ->
                    // Response is newline-delimited JSON, one object per token.
                    stream.forEachLine { line ->
                        if (stopRequested || line.isBlank()) return@forEachLine
                        val chunk = JSONObject(line)
                        chunk.optString("error").takeIf { it.isNotEmpty() }?.let { throw LlmException("Ollama: $it") }
                        val text = PromptFormat.clean(chunk.optString("response"))
                        if (text.isNotEmpty()) {
                            tracker.onToken()
                            onToken(text)
                        }
                        if (chunk.optBoolean("done")) {
                            Log.i(TAG, "Ollama stats: eval_count=${chunk.optLong("eval_count")} " +
                                "eval_duration_ns=${chunk.optLong("eval_duration")} " +
                                "prompt_eval_count=${chunk.optLong("prompt_eval_count")}")
                        }
                    }
                }
            } catch (e: IOException) {
                // Disconnecting in stop() surfaces here as an IOException; that's a normal stop.
                if (!stopRequested) throw LlmException("Ollama request failed: ${e.message}", e)
            }
            tracker.finish().also { Log.i(TAG, "Generation stats: $it") }
        }

    override fun stop() {
        stopRequested = true
        activeConnection?.disconnect()
    }

    override fun close() = stop()

    private fun <T> request(
        method: String,
        path: String,
        body: String?,
        readTimeoutMs: Int,
        read: (java.io.BufferedReader) -> T,
    ): T {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        activeConnection = connection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = readTimeoutMs
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IOException("HTTP $code $error")
            }
            return connection.inputStream.bufferedReader().use(read)
        } finally {
            connection.disconnect()
            activeConnection = null
        }
    }

    private companion object {
        const val TAG = "LearnEdgeLlm"
    }
}

/** Where the Ollama server is and which model it serves. Change these in one place. */
object OllamaConfig {
    /** With `adb reverse tcp:11434 tcp:11434`, the phone's localhost reaches the PC's Ollama. */
    const val BASE_URL = "http://localhost:11434"
    /** "llama3.2" resolves to llama3.2:latest, which is the 3B Instruct model. */
    const val MODEL = "llama3.2"
    const val KEEP_ALIVE = "30m"
}
