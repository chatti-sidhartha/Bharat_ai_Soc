package com.example.learnedge.llm.explain

import com.example.learnedge.data.curriculum.CurriculumParser
import com.example.learnedge.domain.ContentSource
import com.example.learnedge.domain.LevelBand
import com.example.learnedge.llm.MockLlmEngine
import com.example.learnedge.llm.ModelConfig
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplanationTest {
    private val projectDir = System.getProperty("projectDir") ?: error("projectDir system property not set by Gradle")
    private val tracks by lazy { CurriculumParser.parse(File(projectDir, "src/main/assets/curriculum.json").readText()) }
    private val oop by lazy { tracks.flatMap { it.graph.topics }.first { it.id == "j-oop" } }

    private val input by lazy {
        ExplanationInput(
            topicId = "j-oop",
            topicName = oop.name,
            skillName = "Core Java",
            trackName = "Java Backend Developer",
            keyPoints = oop.keyPoints,
            knownPrerequisites = listOf("Methods & Arrays"),
            unlocks = listOf("Inheritance & Polymorphism", "Exception Handling"),
            band = LevelBand.INTERMEDIATE,
        )
    }

    private val good =
        "A class is a blueprint for objects, and an object is one instance that you create with new. " +
            "The constructor initializes the fields when the object is created. " +
            "Encapsulation means you keep the fields private and expose behavior through public methods, so other code cannot break the state. " +
            "Static members belong to the class itself and are shared by every object. " +
            "Picture a class as a cookie cutter and each object as a cookie."

    private fun mockModel(vararg replies: String): Pair<MockLlmEngine, suspend (String, Int) -> String> {
        val engine = MockLlmEngine(replies.toList(), loadDelayMs = 0, firstTokenDelayMs = 0, tokenDelayMs = 0)
        return engine to { prompt, _ ->
            val text = StringBuilder()
            engine.generate(prompt) { text.append(it) }
            text.toString()
        }
    }

    // --- LevelBand ---

    @Test
    fun levelBand_followsTheMasteryThresholds() {
        assertEquals(LevelBand.BEGINNER, LevelBand.of(0.0))
        assertEquals(LevelBand.BEGINNER, LevelBand.of(0.299))
        assertEquals(LevelBand.INTERMEDIATE, LevelBand.of(0.3))
        assertEquals(LevelBand.INTERMEDIATE, LevelBand.of(0.699))
        assertEquals(LevelBand.REVISION, LevelBand.of(0.7))
        assertEquals(LevelBand.REVISION, LevelBand.of(1.0))
    }

    // --- ExplanationValidator ---

    @Test
    fun validator_acceptsAGoodExplanation() {
        val result = ExplanationValidator.validate(good, input.keyPoints)
        assertTrue(result.toString(), result is ExplanationValidation.Valid)
    }

    @Test
    fun validator_enforcesTheWordLimits() {
        assertTrue(ExplanationValidator.validate("A class is a blueprint.", input.keyPoints).toString().contains("too short"))
        assertTrue(ExplanationValidator.validate("class ".repeat(ExplanationValidator.MAX_WORDS + 1), input.keyPoints).toString().contains("too long"))
    }

    @Test
    fun validator_rejectsARepeatedSentence() {
        val repeated = good + " A class is a blueprint for objects, and an object is one instance that you create with new."
        assertTrue(ExplanationValidator.validate(repeated, input.keyPoints).toString().contains("repeats"))
    }

    @Test
    fun validator_rejectsALoopOfTheSamePhrase() {
        val loop = "The object keeps the state safe because the class hides the fields " +
            "the object keeps the state safe and the object keeps the state safe and the object keeps the state safe " +
            "so you can always trust the object, the class, and the constructor of every instance in the program today."
        val result = ExplanationValidator.validate(loop, input.keyPoints)
        assertTrue(result.toString(), result is ExplanationValidation.Invalid && result.reason.contains("repeats"))
    }

    @Test
    fun validator_rejectsJsonInsteadOfPlainText() {
        assertTrue(ExplanationValidator.validate("{\"explanation\": \"$good\"}", input.keyPoints).toString().contains("JSON"))
    }

    @Test
    fun validator_requiresAtLeastTwoKeyTerms() {
        val offTopic = "Programming is a wonderful journey for anyone who enjoys solving problems step by step and learning new ideas " +
            "every single day while building small projects that grow into larger ones over many months of steady practice and curiosity. " +
            "It takes patience and time."
        val result = ExplanationValidator.validate(offTopic, input.keyPoints)
        assertTrue(result.toString(), result is ExplanationValidation.Invalid && result.reason.contains("key terms"))
    }

    @Test
    fun keyTerms_areTheDistinctiveWordsOfTheKeyPoints() {
        val terms = ExplanationValidator.keyTerms(input.keyPoints)
        assertTrue(terms.containsAll(listOf("blueprint", "constructor", "encapsulation", "static")))
        assertFalse("filler words are not key terms", terms.any { it in listOf("with", "that", "each") })
    }

    // --- ExplanationPrompt ---

    @Test
    fun prompt_staysUnder900Characters_forEveryTopic_band_andRetry() {
        var longest = 0
        tracks.forEach { track ->
            val graph = track.graph
            graph.topics.forEach { topic ->
                // Worst case: the longest prerequisite and dependent names the topic has.
                val known = graph.getPrerequisites(topic.id).map { graph.topic(it).name }.sortedByDescending { it.length }.take(4)
                val unlocks = graph.getDependents(topic.id).map { graph.topic(it).name }.sortedByDescending { it.length }.take(3)
                LevelBand.entries.forEach { band ->
                    val request = ExplanationInput(topic.id, topic.name, track.skill(topic.skillId).name, track.name, topic.keyPoints, known, unlocks, band)
                    val first = ExplanationPrompt.build(request)
                    val retry = ExplanationPrompt.build(request, "too short: 12 words, need at least ${ExplanationValidator.MIN_WORDS}")
                    longest = maxOf(longest, first.length, retry.length)
                    assertTrue("${topic.id}/$band prompt is ${first.length} characters", first.length < ExplanationPrompt.MAX_PROMPT_CHARS)
                    assertTrue("${topic.id}/$band retry prompt is ${retry.length} characters", retry.length < ExplanationPrompt.MAX_PROMPT_CHARS)
                    assertTrue("${topic.id} lost its first key point", first.contains(topic.keyPoints.first().take(40)))
                }
            }
        }
        assertTrue("longest prompt: $longest", longest < ExplanationPrompt.MAX_PROMPT_CHARS)
    }

    @Test
    fun prompt_isGroundedInTheGraphAndAsksForPlainText() {
        val prompt = ExplanationPrompt.build(input)
        assertTrue(prompt.startsWith("<|begin_of_text|><|start_header_id|>system<|end_header_id|>"))
        assertTrue(prompt.contains("You already know: Methods & Arrays."))
        assertTrue(prompt.contains("This unlocks: Inheritance & Polymorphism, Exception Handling."))
        assertTrue(prompt.contains("Level: intermediate"))
        assertTrue(prompt.contains("plain text") && prompt.contains("150 words") && prompt.contains("No JSON"))
        assertTrue(prompt.contains("- ${oop.keyPoints[0]}"))
        assertFalse("the model is not asked for a JSON object", prompt.contains("{\""))
    }

    @Test
    fun prompt_pitchesEachBandDifferently() {
        val prompts = LevelBand.entries.map { ExplanationPrompt.build(input.copy(band = it)) }
        assertEquals(3, prompts.toSet().size)
        assertTrue(prompts[0].contains("beginner") && prompts[1].contains("intermediate") && prompts[2].contains("revision"))
    }

    // --- ExplanationGenerator with the Mock engine ---

    @Test
    fun generator_usesAValidAnswerOnTheFirstAttempt() = runBlocking {
        val (engine, model) = mockModel(good)
        val result = ExplanationGenerator().generate(input, model)
        assertEquals(1, result.modelCalls)
        assertEquals(ContentSource.AI, result.source)
        assertEquals(1, engine.prompts.size)
        assertTrue(result.text.startsWith("A class is a blueprint"))
    }

    @Test
    fun generator_retriesWithTheRejectionReasonInThePrompt() = runBlocking {
        val (engine, model) = mockModel("Too short.", good)
        val result = ExplanationGenerator().generate(input, model)
        assertEquals(2, result.modelCalls)
        assertEquals(ContentSource.AI, result.source)
        assertTrue(engine.prompts[1].contains("Your last answer was rejected: too short"))
    }

    @Test
    fun generator_fallsBackToTheBundledKeyPointsAfterTwoRetries() = runBlocking {
        val (engine, model) = mockModel("Too short.")
        val result = ExplanationGenerator().generate(input, model)
        assertEquals(3, engine.prompts.size) // first try + 2 retries, never more
        assertEquals(3, result.failures.size)
        assertEquals(ContentSource.BUNDLED, result.source)
        assertTrue(result.text.startsWith("Key points about Classes & Objects:"))
        oop.keyPoints.forEach { assertTrue(result.text.contains(it)) }
    }

    @Test
    fun generator_fallsBackWhenGenerationThrows() = runBlocking {
        val result = ExplanationGenerator().generate(input, { _, _ -> throw IllegalStateException("engine closed") })
        assertEquals(ContentSource.BUNDLED, result.source)
        assertTrue(result.failures.all { it.contains("engine closed") })
    }

    @Test
    fun generator_withoutAModel_isBundledImmediately() = runBlocking {
        val result = ExplanationGenerator().generate(input, model = null)
        assertEquals(0, result.modelCalls)
        assertEquals(ContentSource.BUNDLED, result.source)
    }

    @Test
    fun generator_canBeForcedToFail_soTheFallbackCanBeDemonstrated() = runBlocking {
        val (engine, model) = mockModel(good)
        val result = ExplanationGenerator().generate(input, model) { ExplanationValidation.Invalid("forced validation failure") }
        assertEquals(3, engine.prompts.size)
        assertEquals(ContentSource.BUNDLED, result.source)
        assertEquals(List(3) { "forced validation failure" }, result.failures)
    }

    // --- Temperature per request ---

    @Test
    fun engine_receivesTheTemperatureOfEachRequest() = runBlocking {
        val engine = MockLlmEngine(listOf("ok"), loadDelayMs = 0, firstTokenDelayMs = 0, tokenDelayMs = 0)
        engine.generate("a", 0.2f) {}
        engine.generate("b") {}
        assertEquals(listOf(0.2f, ModelConfig.TEMPERATURE), engine.temperatures)
    }
}
