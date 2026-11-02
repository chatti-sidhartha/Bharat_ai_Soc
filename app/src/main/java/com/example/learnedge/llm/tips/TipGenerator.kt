package com.example.learnedge.llm.tips

import kotlin.coroutines.cancellation.CancellationException

/** Text that never needs a model: built from the curriculum's topic descriptions. */
object RuleBasedTips {
    fun tip(topic: TipTopic): String =
        "Spend ${topic.minutes} min on ${topic.name}: ${topic.description.trimEnd('.')}. " +
            "Finish by explaining it in your own words."

    fun summary(input: TipInput): String {
        if (input.topics.isEmpty()) return "No study is planned for today."
        val minutes = input.topics.sumOf { it.minutes }
        val gap = input.gaps.firstOrNull()?.let { " Your biggest gap is ${it.skillName}." }.orEmpty()
        val count = if (input.topics.size == 1) "1 topic" else "${input.topics.size} topics"
        return "Today: $count, $minutes min. Start with ${input.topics.first().name}.$gap"
    }

    fun all(input: TipInput) = DailyTips(
        summary = summary(input),
        summarySource = TipSource.RULE_BASED,
        tips = input.topics.associate { it.id to Tip(tip(it), TipSource.RULE_BASED) },
    )
}

/**
 * Asks the model for tips, validates the answer, retries at most [maxRetries] times with a
 * stricter prompt, then falls back to [RuleBasedTips]. The plan is input only: the model can't
 * change which topics are studied.
 */
class TipGenerator(private val maxRetries: Int = 2) {
    /**
     * @param model runs one generation and returns its full text, or is null when no model is
     *   available (then rule-based text is returned right away). `attempt` starts at 1.
     */
    suspend fun generate(input: TipInput, model: (suspend (prompt: String, attempt: Int) -> String)?): GeneratedTips {
        if (input.topics.isEmpty() || model == null) {
            return GeneratedTips(RuleBasedTips.all(input), modelCalls = 0, failures = emptyList())
        }
        val topicIds = input.topics.mapTo(LinkedHashSet()) { it.id }
        val failures = ArrayList<String>()
        for (attempt in 1..1 + maxRetries) {
            val prompt = if (attempt == 1) PromptBuilder.build(input) else PromptBuilder.buildStrict(input, failures.last())
            val output = try {
                model(prompt, attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "generation failed: ${e.message ?: e.javaClass.simpleName}"
                continue
            }
            when (val result = OutputValidator.validate(withPrefix(output), topicIds)) {
                is Validation.Valid -> return GeneratedTips(merge(input, result), attempt, failures)
                is Validation.Invalid -> failures += result.reason
            }
        }
        return GeneratedTips(RuleBasedTips.all(input), modelCalls = 1 + maxRetries, failures = failures)
    }

    /**
     * The prompt pre-fills `{"days": [`, so the model normally continues with the first day object
     * (which itself starts with `{`). Only an answer that opens its own `{"days"` is used as is.
     */
    private fun withPrefix(output: String): String =
        if (OWN_ROOT.containsMatchIn(output)) output else PromptBuilder.ASSISTANT_PREFIX + output

    private companion object {
        val OWN_ROOT = Regex("""\{\s*"days"\s*:""")
    }

    /** AI tips where the model wrote one; rule-based text for any topic it skipped. */
    private fun merge(input: TipInput, valid: Validation.Valid) = DailyTips(
        summary = valid.summary,
        summarySource = TipSource.AI,
        tips = input.topics.associate { topic ->
            topic.id to (valid.tips[topic.id]?.let { Tip(it, TipSource.AI) } ?: Tip(RuleBasedTips.tip(topic), TipSource.RULE_BASED))
        },
    )
}
