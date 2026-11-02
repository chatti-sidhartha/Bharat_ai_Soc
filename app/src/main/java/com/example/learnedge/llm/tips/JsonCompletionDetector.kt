package com.example.learnedge.llm.tips

/**
 * Watches streamed model output and reports when the top-level JSON object has closed, so
 * generation can stop instead of running on after the answer. Braces inside strings are ignored.
 */
class JsonCompletionDetector(prefix: String = PromptBuilder.ASSISTANT_PREFIX) {
    private var depth = 0
    private var started = false
    private var inString = false
    private var escaped = false

    var complete = false
        private set

    init {
        feed(prefix)
    }

    /** Feeds the next chunk; returns true once the outermost object or array has closed. */
    fun feed(chunk: String): Boolean {
        for (c in chunk) {
            if (complete) break
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> { depth++; started = true }
                '}', ']' -> {
                    depth--
                    if (started && depth <= 0) complete = true
                }
            }
        }
        return complete
    }
}
