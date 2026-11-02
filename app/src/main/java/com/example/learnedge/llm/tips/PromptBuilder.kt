package com.example.learnedge.llm.tips

import com.example.learnedge.llm.PromptFormat
import java.util.Locale

/** Builds Llama 3.2 Instruct prompts that ask for today's tips as JSON only. */
object PromptBuilder {
    /**
     * Appended after the assistant header so the model's answer already starts inside the JSON
     * object; small models then stay in JSON instead of chatting. The validator re-adds it.
     */
    const val ASSISTANT_PREFIX = "{\"days\": ["

    private const val SYSTEM =
        "You are LearnEdge, a friendly study coach. You write short, practical study tips. " +
            "The study plan is already decided: never add, remove, rename or reorder topics. " +
            "Reply with a single JSON object and nothing else."

    fun build(input: TipInput): String =
        PromptFormat.llama3(SYSTEM, emptyList(), userMessage(input, retryReason = null)) + ASSISTANT_PREFIX

    /** Used for retries: repeats the rules and says why the previous answer was rejected. */
    fun buildStrict(input: TipInput, retryReason: String): String =
        PromptFormat.llama3(SYSTEM, emptyList(), userMessage(input, retryReason)) + ASSISTANT_PREFIX

    private fun userMessage(input: TipInput, retryReason: String?): String = buildString {
        appendLine("Learner goal: ${input.trackName}.")
        if (input.interests.isNotBlank()) appendLine("Interests: ${input.interests.trim()}.")
        appendLine("Study time: ${input.dailyMinutes} minutes per day. Deadline: ${input.deadline}.")
        if (input.gaps.isNotEmpty()) {
            appendLine("Biggest skill gaps (level now -> target, out of 10):")
            input.gaps.forEach {
                appendLine("- ${it.skillName}: ${String.format(Locale.US, "%.1f", it.currentLevel)} -> ${it.targetLevel}")
            }
        }
        appendLine()
        appendLine("Today's plan for ${input.date}:")
        input.topics.forEach { appendLine("- topicId \"${it.id}\": ${it.name} (${it.minutes} min). ${it.description}") }
        appendLine()
        appendLine("Write one tip of one or two sentences for each topic, and a one-sentence summary of the day.")
        appendLine("Answer with JSON only, exactly in this shape:")
        append("{\"days\": [")
        append(input.topics.joinToString(", ") { "{\"date\": \"${input.date}\", \"topicId\": \"${it.id}\", \"tip\": \"...\"}" })
        appendLine("], \"summary\": \"...\"}")
        if (retryReason != null) {
            appendLine()
            appendLine("Your previous answer was rejected: $retryReason")
            appendLine("Output ONLY the JSON object, with no text before or after it.")
            appendLine("Use only these topicIds: ${input.topics.joinToString { "\"${it.id}\"" }}.")
            append("Keep every tip under ${OutputValidator.MAX_TIP_CHARS} characters and the summary under ${OutputValidator.MAX_SUMMARY_CHARS}.")
        }
    }
}
