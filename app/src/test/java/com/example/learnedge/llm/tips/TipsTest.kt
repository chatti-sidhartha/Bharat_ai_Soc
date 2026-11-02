package com.example.learnedge.llm.tips

import com.example.learnedge.llm.MockLlmEngine
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TipsTest {
    private val today = LocalDate.of(2026, 11, 3)
    private val input = TipInput(
        date = today,
        trackName = "Java Backend Developer",
        interests = "cricket statistics",
        dailyMinutes = 120,
        deadline = LocalDate.of(2026, 12, 31),
        gaps = listOf(GapLine("Spring Boot", 2.6, 7), GapLine("Testing", 1.8, 6)),
        topics = listOf(
            TipTopic("sp-di", "Spring Core & Dependency Injection", "Inversion of control, beans and constructor injection.", 75),
            TipTopic("s-joins", "Joins", "INNER and LEFT joins.", 45),
        ),
    )
    private val ids = setOf("sp-di", "s-joins")

    // What the model writes after the pre-filled `{"days": [`.
    private val validContinuation =
        """{"date": "2026-11-03", "topicId": "sp-di", "tip": "Build a tiny app with two beans and inject one into the other."}, """ +
            """{"date": "2026-11-03", "topicId": "s-joins", "tip": "Join two small tables of cricket players and matches."}], """ +
            """"summary": "Focus on Spring basics, then practise SQL joins."}"""
    private val unknownTopicContinuation =
        """{"date": "2026-11-03", "topicId": "kotlin-coroutines", "tip": "Learn coroutines today, they are great."}], """ +
            """"summary": "A day of something else entirely."}"""

    private fun mockModel(vararg replies: String): Pair<MockLlmEngine, suspend (String, Int) -> String> {
        val engine = MockLlmEngine(replies.toList(), loadDelayMs = 0, firstTokenDelayMs = 0, tokenDelayMs = 0)
        return engine to { prompt, _ ->
            val text = StringBuilder()
            engine.generate(prompt) { text.append(it) }
            text.toString()
        }
    }

    // --- OutputValidator ---

    @Test
    fun validator_acceptsValidJson() {
        val result = OutputValidator.validate(PromptBuilder.ASSISTANT_PREFIX + validContinuation, ids)
        assertTrue(result.toString(), result is Validation.Valid)
        result as Validation.Valid
        assertEquals(setOf("sp-di", "s-joins"), result.tips.keys)
        assertEquals("Focus on Spring basics, then practise SQL joins.", result.summary)
    }

    @Test
    fun validator_ignoresTextAndCodeFencesAroundTheObject() {
        val wrapped = "Sure! Here is your plan:\n```json\n" + PromptBuilder.ASSISTANT_PREFIX + validContinuation + "\n```"
        assertTrue(OutputValidator.validate(wrapped, ids) is Validation.Valid)
    }

    @Test
    fun validator_rejectsMalformedJson() {
        val truncated = PromptBuilder.ASSISTANT_PREFIX + """{"date": "2026-11-03", "topicId": "sp-di", "tip": "Unclosed}"""
        val result = OutputValidator.validate(truncated, ids)
        assertTrue(result.toString(), result is Validation.Invalid)
        assertTrue(OutputValidator.validate("no json at all", ids) is Validation.Invalid)
    }

    @Test
    fun validator_rejectsUnknownTopicId() {
        val result = OutputValidator.validate(PromptBuilder.ASSISTANT_PREFIX + unknownTopicContinuation, ids)
        assertEquals(Validation.Invalid("unknown topicId \"kotlin-coroutines\""), result)
    }

    @Test
    fun validator_checksSchemaAndLengths() {
        val noSummary = """{"days": [{"date": "2026-11-03", "topicId": "sp-di", "tip": "A perfectly fine tip here."}]}"""
        assertEquals(Validation.Invalid("missing \"summary\" string"), OutputValidator.validate(noSummary, ids))

        val longTip = "x".repeat(OutputValidator.MAX_TIP_CHARS + 1)
        val tooLong = """{"days": [{"date": "2026-11-03", "topicId": "sp-di", "tip": "$longTip"}], "summary": "A fine summary."}"""
        assertTrue(OutputValidator.validate(tooLong, ids).toString().contains("longer than"))

        val badDate = """{"days": [{"date": "tomorrow", "topicId": "sp-di", "tip": "A perfectly fine tip here."}], "summary": "A fine summary."}"""
        assertTrue(OutputValidator.validate(badDate, ids).toString().contains("YYYY-MM-DD"))

        val numberTip = """{"days": [{"date": "2026-11-03", "topicId": "sp-di", "tip": 42}], "summary": "A fine summary."}"""
        assertTrue(OutputValidator.validate(numberTip, ids) is Validation.Invalid)
    }

    // --- TipGenerator with the Mock engine ---

    @Test
    fun generator_usesValidAnswerOnFirstAttempt() = runBlocking {
        val (engine, model) = mockModel(validContinuation)
        val result = TipGenerator().generate(input, model)
        assertEquals(1, result.modelCalls)
        assertEquals(TipSource.AI, result.tips.summarySource)
        assertTrue(result.tips.tips.values.all { it.source == TipSource.AI })
        assertEquals(1, engine.prompts.size)
    }

    @Test
    fun generator_retriesWithStricterPromptAfterMalformedJson() = runBlocking {
        val (engine, model) = mockModel("this is not json", validContinuation)
        val result = TipGenerator().generate(input, model)
        assertEquals(2, result.modelCalls)
        assertEquals(TipSource.AI, result.tips.summarySource)
        assertTrue(engine.prompts[1].contains("Your previous answer was rejected"))
        assertTrue(engine.prompts[1].contains("Use only these topicIds"))
    }

    @Test
    fun generator_fallsBackToRuleBasedAfterTwoRetries() = runBlocking {
        val (engine, model) = mockModel(unknownTopicContinuation)
        val result = TipGenerator().generate(input, model)
        assertEquals(3, engine.prompts.size) // first try + 2 retries, never more
        assertEquals(3, result.failures.size)
        assertEquals(TipSource.RULE_BASED, result.tips.summarySource)
        val tip = result.tips.tips.getValue("sp-di")
        assertEquals(TipSource.RULE_BASED, tip.source)
        assertTrue(tip.text, tip.text.contains("Inversion of control"))
        assertEquals(ids, result.tips.tips.keys)
    }

    @Test
    fun generator_fallsBackWhenGenerationThrows() = runBlocking {
        val result = TipGenerator().generate(input) { _, _ -> throw IllegalStateException("engine closed") }
        assertEquals(TipSource.RULE_BASED, result.tips.summarySource)
        assertTrue(result.failures.all { it.contains("engine closed") })
    }

    @Test
    fun generator_withoutModel_isRuleBasedImmediately() = runBlocking {
        val result = TipGenerator().generate(input, model = null)
        assertEquals(0, result.modelCalls)
        assertEquals(TipSource.RULE_BASED, result.tips.summarySource)
    }

    @Test
    fun generator_fillsTopicsTheModelSkippedWithRuleBasedTips() = runBlocking {
        val onlyOne = """{"date": "2026-11-03", "topicId": "sp-di", "tip": "Inject a service into a controller."}], "summary": "Spring first today."}"""
        val (_, model) = mockModel(onlyOne)
        val tips = TipGenerator().generate(input, model).tips.tips
        assertEquals(TipSource.AI, tips.getValue("sp-di").source)
        assertEquals(TipSource.RULE_BASED, tips.getValue("s-joins").source)
    }

    // --- JsonCompletionDetector ---

    @Test
    fun completionDetector_firesWhenTheRootObjectCloses_notInsideStrings() {
        val detector = JsonCompletionDetector()
        // The prompt pre-filled `{"days": [`; braces inside the tip string must not count.
        assertTrue(!detector.feed("""{"date": "2026-11-03", "topicId": "sp-di", "tip": "Use {braces} and ] here."}"""))
        assertTrue(!detector.feed("""], "summary": "Say \"hi\" to Spring.""""))
        assertTrue(detector.feed("""} and then the model keeps talking"""))
    }

    @Test
    fun completionDetector_isNotCompleteForTruncatedOutput() {
        assertTrue(!JsonCompletionDetector().feed("""{"date": "2026-11-03", "topicId": "sp-di", "tip": "cut off"""))
    }

    // --- PromptBuilder ---

    @Test
    fun prompt_usesLlama3TemplateAndListsOnlyTodaysTopics() {
        val prompt = PromptBuilder.build(input)
        assertTrue(prompt.startsWith("<|begin_of_text|><|start_header_id|>system<|end_header_id|>"))
        assertTrue(prompt.contains("<|start_header_id|>assistant<|end_header_id|>"))
        assertTrue(prompt.endsWith(PromptBuilder.ASSISTANT_PREFIX))
        assertTrue(prompt.contains("topicId \"sp-di\"") && prompt.contains("topicId \"s-joins\""))
        assertTrue(prompt.contains("Spring Boot: 2.6 -> 7"))
        assertTrue(prompt.contains("cricket statistics"))
    }
}
