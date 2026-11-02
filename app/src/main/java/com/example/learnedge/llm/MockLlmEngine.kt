package com.example.learnedge.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Streams canned text so the UI can be exercised without a model on the device. */
class MockLlmEngine : LlmEngine {
    override val name = "Mock"
    override var loadTimeMs: Long? = null
        private set

    @Volatile private var stopRequested = false

    override suspend fun load() {
        if (loadTimeMs != null) return
        delay(LOAD_DELAY_MS)
        loadTimeMs = LOAD_DELAY_MS
    }

    override suspend fun generate(prompt: String, onToken: (String) -> Unit): GenerationStats =
        withContext(Dispatchers.Default) {
            stopRequested = false
            val tracker = StatsTracker()
            delay(FIRST_TOKEN_DELAY_MS)
            for (word in CANNED_REPLY.split(" ")) {
                if (stopRequested) break
                tracker.onToken()
                onToken("$word ")
                delay(TOKEN_DELAY_MS)
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
