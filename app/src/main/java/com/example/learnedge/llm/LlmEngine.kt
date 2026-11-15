package com.example.learnedge.llm

import android.os.SystemClock

/**
 * A swappable text-generation backend. Implementations must do their heavy work off the main
 * thread; [generate] suspends until generation finishes (or [stop] is called).
 */
interface LlmEngine {
    val name: String

    /** Wall-clock time of the last successful [load], or null if not loaded. */
    val loadTimeMs: Long?

    /** Loads the model. Safe to call more than once. Throws [LlmException] on failure. */
    suspend fun load()

    /**
     * Generates a completion for an already-formatted [prompt]. [onToken] is called for every
     * token, possibly from a background thread. [temperature] applies to this call only. Returns
     * timing stats for this call.
     */
    suspend fun generate(prompt: String, temperature: Float = ModelConfig.TEMPERATURE, onToken: (String) -> Unit): GenerationStats

    /** Requests the current generation to stop early. Safe to call from any thread. */
    fun stop()

    /** Frees native resources. The engine can't be used afterwards. */
    fun close()
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class GenerationStats(
    val timeToFirstTokenMs: Long?,
    val tokensPerSecond: Double,
    val totalTimeMs: Long,
    val tokenCount: Int,
)

/** Measures one generation call. Create it right before generation starts. */
class StatsTracker {
    private val startMs = SystemClock.elapsedRealtime()
    private var firstTokenMs: Long? = null
    private var tokenCount = 0

    fun onToken() {
        if (firstTokenMs == null) firstTokenMs = SystemClock.elapsedRealtime()
        tokenCount++
    }

    fun finish(): GenerationStats {
        val endMs = SystemClock.elapsedRealtime()
        val first = firstTokenMs
        // Decode speed: tokens after the first one, over the time spent producing them.
        val decodeMs = if (first != null) endMs - first else 0L
        val tokensPerSecond = when {
            tokenCount > 1 && decodeMs > 0 -> (tokenCount - 1) * 1000.0 / decodeMs
            tokenCount > 0 && endMs > startMs -> tokenCount * 1000.0 / (endMs - startMs)
            else -> 0.0
        }
        return GenerationStats(
            timeToFirstTokenMs = first?.let { it - startMs },
            tokensPerSecond = tokensPerSecond,
            totalTimeMs = endMs - startMs,
            tokenCount = tokenCount,
        )
    }
}
