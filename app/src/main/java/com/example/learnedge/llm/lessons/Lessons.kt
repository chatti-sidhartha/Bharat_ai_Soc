package com.example.learnedge.llm.lessons

import com.example.learnedge.llm.PromptFormat
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Everything the model may know when writing one lesson. The key points are hand-written ground
 * truth: the model only rephrases and illustrates them, it never decides what the topic is.
 */
data class LessonInput(
    val topicId: String,
    val topicName: String,
    val skillName: String,
    val trackName: String,
    val description: String,
    val keyPoints: List<String>,
)

enum class LessonSource { AI, BUNDLED }

data class Lesson(val explanation: String, val example: String?, val source: LessonSource)

/** Result of [LessonGenerator.generate]: the lesson, how many model calls it took and why attempts failed. */
data class GeneratedLesson(val lesson: Lesson, val modelCalls: Int, val failures: List<String>)

/** Text that never needs a model: the topic description from the curriculum. */
object BundledLesson {
    fun build(input: LessonInput) = Lesson(input.description.trim(), example = null, source = LessonSource.BUNDLED)
}

/** Builds Llama 3.2 Instruct prompts that ask for one lesson as JSON only. */
object LessonPromptBuilder {
    /**
     * Appended after the assistant header so the answer starts inside the explanation string; small
     * models then stay in JSON instead of chatting. The generator re-adds it before validating.
     */
    fun assistantPrefix(topicId: String) = "{\"topicId\": \"$topicId\", \"explanation\": \""

    private const val SYSTEM =
        "You are LearnEdge, a patient tutor. You explain one topic to a learner using only the facts you are given. " +
            "Reply with a single JSON object and nothing else."

    fun build(input: LessonInput): String =
        PromptFormat.llama3(SYSTEM, emptyList(), userMessage(input, retryReason = null)) + assistantPrefix(input.topicId)

    /** Used for retries: repeats the rules and says why the previous answer was rejected. */
    fun buildStrict(input: LessonInput, retryReason: String): String =
        PromptFormat.llama3(SYSTEM, emptyList(), userMessage(input, retryReason)) + assistantPrefix(input.topicId)

    private fun userMessage(input: LessonInput, retryReason: String?): String = buildString {
        appendLine("Learner goal: ${input.trackName}. Skill: ${input.skillName}.")
        appendLine("Topic: ${input.topicName}. ${input.description}")
        appendLine("Facts you must stay consistent with:")
        input.keyPoints.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Write an explanation of 3 to 5 short sentences for a beginner, and one small example.")
        appendLine("Put each text on a single line: no line breaks inside the strings.")
        appendLine("Answer with JSON only, exactly in this shape:")
        append("{\"topicId\": \"${input.topicId}\", \"explanation\": \"...\", \"example\": \"...\"}")
        if (retryReason != null) {
            appendLine()
            appendLine()
            appendLine("Your previous answer was rejected: $retryReason")
            appendLine("Output ONLY the JSON object, with no text before or after it.")
            append("Use topicId \"${input.topicId}\". Keep the explanation under ${LessonValidator.MAX_EXPLANATION} characters and the example under ${LessonValidator.MAX_EXAMPLE}.")
        }
    }
}

sealed interface LessonValidation {
    data class Valid(val explanation: String, val example: String) : LessonValidation
    data class Invalid(val reason: String) : LessonValidation
}

/** Accepts model output only if it is the expected JSON about the requested topic. */
object LessonValidator {
    const val MIN_EXPLANATION = 60
    const val MAX_EXPLANATION = 700
    const val MIN_EXAMPLE = 10
    const val MAX_EXAMPLE = 500

    fun validate(raw: String, topicId: String): LessonValidation {
        // Raw line breaks inside a JSON string are invalid; outside strings they are plain whitespace, so flattening is safe.
        val flat = raw.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        // Tolerate text or ``` fences around the object; everything outside the outer braces is ignored.
        val start = flat.indexOf('{')
        val end = flat.lastIndexOf('}')
        if (start < 0 || end <= start) return LessonValidation.Invalid("no JSON object found")

        val root = try {
            Json.parseToJsonElement(flat.substring(start, end + 1))
        } catch (e: SerializationException) {
            return LessonValidation.Invalid("malformed JSON (${e.message?.lineSequence()?.firstOrNull()?.take(80)})")
        } catch (e: IllegalArgumentException) {
            return LessonValidation.Invalid("malformed JSON")
        }
        val obj = root as? JsonObject ?: return LessonValidation.Invalid("top level is not a JSON object")
        val id = obj.string("topicId") ?: return LessonValidation.Invalid("missing \"topicId\" string")
        if (id != topicId) return LessonValidation.Invalid("unknown topicId \"$id\"")
        val explanation = obj.string("explanation")?.trim() ?: return LessonValidation.Invalid("missing \"explanation\" string")
        val example = obj.string("example")?.trim() ?: return LessonValidation.Invalid("missing \"example\" string")
        lengthError("explanation", explanation, MIN_EXPLANATION, MAX_EXPLANATION)?.let { return LessonValidation.Invalid(it) }
        lengthError("example", example, MIN_EXAMPLE, MAX_EXAMPLE)?.let { return LessonValidation.Invalid(it) }
        return LessonValidation.Valid(explanation, example)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun lengthError(what: String, text: String, min: Int, max: Int): String? = when {
        text.length < min -> "$what is too short"
        text.length > max -> "$what is longer than $max characters"
        else -> null
    }
}

/**
 * Asks the model for a lesson, validates the answer, retries at most [maxRetries] times with a
 * stricter prompt, then falls back to the bundled text. The topic is input only: the model cannot
 * change what is being taught.
 */
class LessonGenerator(private val maxRetries: Int = 2) {
    /**
     * @param model runs one generation and returns its full text, or is null when no model is
     *   available (then the bundled lesson is returned right away). `attempt` starts at 1.
     */
    suspend fun generate(input: LessonInput, model: (suspend (prompt: String, attempt: Int) -> String)?): GeneratedLesson {
        if (model == null) return GeneratedLesson(BundledLesson.build(input), modelCalls = 0, failures = emptyList())
        val failures = ArrayList<String>()
        for (attempt in 1..1 + maxRetries) {
            val prompt = if (attempt == 1) LessonPromptBuilder.build(input) else LessonPromptBuilder.buildStrict(input, failures.last())
            val output = try {
                model(prompt, attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "generation failed: ${e.message ?: e.javaClass.simpleName}"
                continue
            }
            when (val result = LessonValidator.validate(withPrefix(input.topicId, output), input.topicId)) {
                is LessonValidation.Valid ->
                    return GeneratedLesson(Lesson(result.explanation, result.example, LessonSource.AI), attempt, failures)
                is LessonValidation.Invalid -> failures += result.reason
            }
        }
        return GeneratedLesson(BundledLesson.build(input), modelCalls = 1 + maxRetries, failures = failures)
    }

    /** The prompt pre-fills the start of the object; only an answer that opens its own `{"topicId"` is used as is. */
    private fun withPrefix(topicId: String, output: String): String =
        if (OWN_ROOT.containsMatchIn(output)) output else LessonPromptBuilder.assistantPrefix(topicId) + output

    private companion object {
        val OWN_ROOT = Regex("""\{\s*"topicId"\s*:""")
    }
}
