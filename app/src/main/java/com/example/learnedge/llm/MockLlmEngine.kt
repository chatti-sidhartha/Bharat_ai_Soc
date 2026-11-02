package com.example.learnedge.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Streams canned text so the UI can be exercised without a model on the device. Tests pass
 * [scriptedReplies] (one per generate call, the last one repeats) and zero delays.
 */
class MockLlmEngine(
    private val scriptedReplies: List<String> = emptyList(),
    private val loadDelayMs: Long = LOAD_DELAY_MS,
    private val firstTokenDelayMs: Long = FIRST_TOKEN_DELAY_MS,
    private val tokenDelayMs: Long = TOKEN_DELAY_MS,
) : LlmEngine {
    override val name = "Mock"
    override var loadTimeMs: Long? = null
        private set

    /** Prompts received so far, so tests can check what was asked. */
    val prompts: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    @Volatile private var stopRequested = false

    override suspend fun load() {
        if (loadTimeMs != null) return
        delay(loadDelayMs)
        loadTimeMs = loadDelayMs
    }

    override suspend fun generate(prompt: String, onToken: (String) -> Unit): GenerationStats =
        withContext(Dispatchers.Default) {
            stopRequested = false
            prompts += prompt
            val reply = scriptedReplies.getOrNull(prompts.size - 1) ?: scriptedReplies.lastOrNull() ?: CANNED_REPLY
            val tracker = StatsTracker()
            delay(firstTokenDelayMs)
            for (word in reply.split(" ")) {
                if (stopRequested) break
                tracker.onToken()
                onToken("$word ")
                delay(tokenDelayMs)
            }
            tracker.finish()
        }

    override fun stop() {
        stopRequested = true
    }

    override fun close() = Unit

    private companion object {
        const val LOAD_DELAY_MS = 600L
        const val FIRST_TOKEN_DELAY_MS = 300L
        const val TOKEN_DELAY_MS = 40L
        const val CANNED_REPLY =
            "This is the mock engine, so no real model ran. To learn Java backend development, " +
                "start with core Java and OOP, then SQL and database design, then Spring Boot " +
                "and REST APIs. Practise with Git every day and build one small project per topic. " +
                "Push a .pte model and tokenizer to the phone and switch to On-device for real answers."
    }
}
