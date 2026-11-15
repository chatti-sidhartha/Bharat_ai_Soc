package com.example.learnedge.llm.lessons

import com.example.learnedge.llm.MockLlmEngine
import com.example.learnedge.llm.tips.JsonCompletionDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonsTest {
    private val input = LessonInput(
        topicId = "j-oop",
        topicName = "Classes & Objects",
        skillName = "Core Java",
        trackName = "Java Backend Developer",
        description = "Fields, constructors, methods, encapsulation, static members and object references.",
        keyPoints = listOf("A class is a blueprint.", "Keep fields private.", "static members belong to the class."),
    )
    private val prefix = LessonPromptBuilder.assistantPrefix("j-oop")

    // What the model writes after the pre-filled `{"topicId": "j-oop", "explanation": "`.
    private val validContinuation =
        """A class is a blueprint and an object is one instance of it. The constructor sets up the fields when you call new, """ +
            """and private fields keep the state safe.", "example": "Student s = new Student();"}"""
    private val wrongTopicOwnRoot =
        """{"topicId": "j-lambdas", "explanation": "${"x".repeat(80)}", "example": "list.stream();"}"""

    private fun mockModel(vararg replies: String): Pair<MockLlmEngine, suspend (String, Int) -> String> {
        val engine = MockLlmEngine(replies.toList(), loadDelayMs = 0, firstTokenDelayMs = 0, tokenDelayMs = 0)
        return engine to { prompt, _ ->
            val text = StringBuilder()
            engine.generate(prompt) { text.append(it) }
            text.toString()
        }
    }

    // --- LessonValidator ---

    @Test
    fun validator_acceptsValidJson() {
        val result = LessonValidator.validate(prefix + validContinuation, "j-oop")
        assertTrue(result.toString(), result is LessonValidation.Valid)
        result as LessonValidation.Valid
        assertTrue(result.explanation.startsWith("A class is a blueprint"))
        assertEquals("Student s = new Student();", result.example)
    }

    @Test
    fun validator_toleratesLineBreaksInsideStrings_andTextAroundTheObject() {
        val withBreaks = "Sure!\n```json\n" + prefix + validContinuation.replace(" The constructor", "\n The constructor") + "\n```"
        assertTrue(LessonValidator.validate(withBreaks, "j-oop") is LessonValidation.Valid)
    }

    @Test
    fun validator_rejectsMalformedJson() {
        val truncated = prefix + "A class is a blueprint and an object is an instance of it, but this answer never closes"
        assertTrue(LessonValidator.validate(truncated, "j-oop") is LessonValidation.Invalid)
        assertTrue(LessonValidator.validate("no json at all", "j-oop") is LessonValidation.Invalid)
    }

    @Test
    fun validator_rejectsAnotherTopicsLesson() {
        assertEquals(LessonValidation.Invalid("unknown topicId \"j-lambdas\""), LessonValidator.validate(wrongTopicOwnRoot, "j-oop"))
    }

    @Test
    fun validator_checksSchemaAndLengths() {
        val noExample = """{"topicId": "j-oop", "explanation": "${"y".repeat(80)}"}"""
        assertEquals(LessonValidation.Invalid("missing \"example\" string"), LessonValidator.validate(noExample, "j-oop"))

        val shortExplanation = """{"topicId": "j-oop", "explanation": "Too short.", "example": "Student s = new Student();"}"""
        assertTrue(LessonValidator.validate(shortExplanation, "j-oop").toString().contains("too short"))

        val longExplanation = """{"topicId": "j-oop", "explanation": "${"z".repeat(LessonValidator.MAX_EXPLANATION + 1)}", "example": "Student s = new Student();"}"""
        assertTrue(LessonValidator.validate(longExplanation, "j-oop").toString().contains("longer than"))

        val numberExample = """{"topicId": "j-oop", "explanation": "${"y".repeat(80)}", "example": 42}"""
        assertTrue(LessonValidator.validate(numberExample, "j-oop") is LessonValidation.Invalid)
    }

    // --- LessonGenerator with the Mock engine ---

    @Test
    fun generator_usesValidAnswerOnFirstAttempt() = runBlocking {
        val (engine, model) = mockModel(validContinuation)
        val result = LessonGenerator().generate(input, model)
        assertEquals(1, result.modelCalls)
        assertEquals(LessonSource.AI, result.lesson.source)
        assertEquals("Student s = new Student();", result.lesson.example)
        assertEquals(1, engine.prompts.size)
    }

    @Test
    fun generator_retriesWithStricterPromptAfterMalformedJson() = runBlocking {
        val (engine, model) = mockModel("this is not json", validContinuation)
        val result = LessonGenerator().generate(input, model)
        assertEquals(2, result.modelCalls)
        assertEquals(LessonSource.AI, result.lesson.source)
        assertTrue(engine.prompts[1].contains("Your previous answer was rejected"))
        assertTrue(engine.prompts[1].contains("Use topicId \"j-oop\""))
    }

    @Test
    fun generator_fallsBackToBundledTextAfterTwoRetries() = runBlocking {
        val (engine, model) = mockModel(wrongTopicOwnRoot)
        val result = LessonGenerator().generate(input, model)
        assertEquals(3, engine.prompts.size) // first try + 2 retries, never more
        assertEquals(3, result.failures.size)
        assertEquals(LessonSource.BUNDLED, result.lesson.source)
        assertEquals(input.description, result.lesson.explanation)
        assertNull(result.lesson.example)
    }

    @Test
    fun generator_fallsBackWhenGenerationThrows() = runBlocking {
        val result = LessonGenerator().generate(input) { _, _ -> throw IllegalStateException("engine closed") }
        assertEquals(LessonSource.BUNDLED, result.lesson.source)
        assertTrue(result.failures.all { it.contains("engine closed") })
    }

    @Test
    fun generator_withoutModel_isBundledImmediately() = runBlocking {
        val result = LessonGenerator().generate(input, model = null)
        assertEquals(0, result.modelCalls)
        assertEquals(LessonSource.BUNDLED, result.lesson.source)
    }

    // --- JsonCompletionDetector with the lesson prefix ---

    @Test
    fun completionDetector_firesWhenTheLessonObjectCloses_notInsideStrings() {
        val detector = JsonCompletionDetector(prefix)
        // The prefix ends inside the explanation string; braces inside strings must not count.
        assertFalse(detector.feed("""A class is a {blueprint} for objects ] and more.", "example": "new Student("""))
        assertTrue(detector.feed(""");"} and then the model keeps talking"""))
    }

    // --- LessonPromptBuilder ---

    @Test
    fun prompt_usesLlama3Template_andGroundsTheModelInTheKeyPoints() {
        val prompt = LessonPromptBuilder.build(input)
        assertTrue(prompt.startsWith("<|begin_of_text|><|start_header_id|>system<|end_header_id|>"))
        assertTrue(prompt.endsWith(prefix))
        assertTrue(prompt.contains("- A class is a blueprint."))
        assertTrue(prompt.contains("\"topicId\": \"j-oop\""))
        assertTrue(prompt.contains("Java Backend Developer"))
    }
}
