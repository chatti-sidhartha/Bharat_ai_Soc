package com.example.learnedge.llm.explain

import com.example.learnedge.domain.ContentSource
import com.example.learnedge.domain.LevelBand
import com.example.learnedge.llm.PromptFormat
import kotlin.coroutines.cancellation.CancellationException

/**
 * Everything the model may know when explaining one topic. It comes from the knowledge graph and
 * the learner's mastery; the model decides none of it, it only writes the words.
 */
data class ExplanationInput(
    val topicId: String,
    val topicName: String,
    val skillName: String,
    val trackName: String,
    /** Hand-written, factual: the model must stay consistent with these. */
    val keyPoints: List<String>,
    /** Names of this topic's prerequisites that the learner has already mastered. */
    val knownPrerequisites: List<String>,
    /** Names of the topics that depend on this one. */
    val unlocks: List<String>,
    val band: LevelBand,
)

/** Result of [ExplanationGenerator.generate]. */
data class GeneratedExplanation(
    val text: String,
    val source: ContentSource,
    val modelCalls: Int,
    /** Why each rejected attempt was rejected, in order. */
    val failures: List<String>,
)

/** Text that never needs a model: the topic's hand-written key points. */
object BundledExplanation {
    fun build(topicName: String, keyPoints: List<String>): String =
        "Key points about $topicName:\n" + keyPoints.joinToString("\n") { "• $it" }
}

/** Builds Llama 3.2 Instruct prompts that ask for a short plain-text explanation. */
object ExplanationPrompt {
    /** Stored with every generated explanation, so older text can be told apart when the prompt changes. */
    const val VERSION = 1

    /** The whole prompt, chat-template tokens included, stays under this many characters. */
    const val MAX_PROMPT_CHARS = 900
    const val MAX_WORDS_ASKED = 150

    private const val SYSTEM = "You are LearnEdge, a patient tutor. Be accurate and concise."
    private const val MAX_REASON_CHARS = 80

    /** [retryReason] is the validator's reason for rejecting the previous answer, or null on the first try. */
    fun build(input: ExplanationInput, retryReason: String? = null): String {
        var known = input.knownPrerequisites.take(4)
        var unlocks = input.unlocks.take(3)
        var points = input.keyPoints
        var guidance = true
        val reason = retryReason?.take(MAX_REASON_CHARS)

        fun render() = PromptFormat.llama3(SYSTEM, emptyList(), userMessage(input, known, unlocks, points, guidance, reason))

        // Drop the least valuable context first until the prompt fits; the key points are kept as long as possible.
        val degrade: List<() -> Unit> = listOf(
            { unlocks = emptyList() },
            { known = known.take(2) },
            { known = emptyList() },
            { guidance = false },
            { points = points.map { it.take(110) } },
            { points = points.take(3).map { it.take(80) } },
        )
        var prompt = render()
        for (step in degrade) {
            if (prompt.length < MAX_PROMPT_CHARS) break
            step()
            prompt = render()
        }
        check(prompt.length < MAX_PROMPT_CHARS) { "Explanation prompt for ${input.topicId} is ${prompt.length} characters" }
        return prompt
    }

    private fun userMessage(
        input: ExplanationInput,
        known: List<String>,
        unlocks: List<String>,
        points: List<String>,
        guidance: Boolean,
        retryReason: String?,
    ): String = buildString {
        appendLine("Topic: ${input.topicName} (${input.skillName}, ${input.trackName}).")
        if (guidance) appendLine(guidanceFor(input.band))
        if (known.isNotEmpty()) appendLine("You already know: ${known.joinToString(", ")}.")
        if (unlocks.isNotEmpty()) appendLine("This unlocks: ${unlocks.joinToString(", ")}.")
        appendLine("Facts:")
        points.forEach { appendLine("- $it") }
        append("Explain in plain text, max $MAX_WORDS_ASKED words, using the facts. No JSON, lists or headings.")
        if (retryReason != null) append("\nYour last answer was rejected: $retryReason. Try again.")
    }

    private fun guidanceFor(band: LevelBand) = when (band) {
        LevelBand.BEGINNER -> "Level: beginner. Use simple words and one everyday analogy."
        LevelBand.INTERMEDIATE -> "Level: intermediate. Skip the basics; show how the pieces fit."
        LevelBand.REVISION -> "Level: revision. Give a quick refresher and one common pitfall."
    }
}

sealed interface ExplanationValidation {
    data class Valid(val text: String) : ExplanationValidation
    data class Invalid(val reason: String) : ExplanationValidation
}

/** Accepts model output only if it is a plain-text explanation of the topic that does not loop. */
object ExplanationValidator {
    const val MIN_WORDS = 40
    const val MAX_WORDS = 200
    const val MIN_KEY_TERMS = 2

    private const val REPEATED_PHRASE_WORDS = 5
    private const val REPEATED_PHRASE_LIMIT = 3
    private val SENTENCE_END = Regex("(?<=[.!?])\\s+")
    private val WHITESPACE = Regex("\\s+")
    private val NON_WORD = Regex("[^a-z0-9 ]")
    private val TOKEN = Regex("[A-Za-z@][A-Za-z0-9_@]*")
    private val STOP = setOf(
        "that", "this", "with", "from", "they", "their", "have", "been", "when", "which", "what", "your", "each",
        "into", "than", "then", "also", "only", "more", "most", "such", "used", "uses", "using", "does", "must",
        "should", "will", "about", "them", "these", "those", "both", "other", "every", "there", "where", "while",
        "because", "without", "between", "after", "before", "under", "over", "same", "always", "never", "often",
        "returns", "make", "makes", "need", "needs", "like", "type", "types",
    )

    fun validate(raw: String, keyPoints: List<String>): ExplanationValidation {
        val text = raw.trim()
        if (text.isEmpty()) return ExplanationValidation.Invalid("the answer is empty")
        if (text.startsWith("{") || text.startsWith("[") || text.contains("\"explanation\"") || text.contains("```")) {
            return ExplanationValidation.Invalid("it looks like JSON or code, not plain text")
        }
        val words = text.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size < MIN_WORDS) return ExplanationValidation.Invalid("too short: ${words.size} words, need at least $MIN_WORDS")
        if (words.size > MAX_WORDS) return ExplanationValidation.Invalid("too long: ${words.size} words, at most $MAX_WORDS")

        repeatedLoop(text, words)?.let { return ExplanationValidation.Invalid(it) }

        val terms = keyTerms(keyPoints)
        val lower = text.lowercase()
        val found = terms.count { Regex("(?<![a-z0-9_])" + Regex.escape(it)).containsMatchIn(lower) }
        val needed = minOf(MIN_KEY_TERMS, terms.size)
        if (found < needed) return ExplanationValidation.Invalid("it mentions only $found of the required $needed key terms")
        return ExplanationValidation.Valid(text)
    }

    /** Words worth looking for: the distinctive words of the key points (long words and acronyms, minus filler). */
    fun keyTerms(keyPoints: List<String>): Set<String> =
        keyPoints.flatMap { point -> TOKEN.findAll(point).map { it.value }.toList() }
            .filter { it.length >= 4 || (it.length == 3 && it == it.uppercase()) }
            .map { it.lowercase() }
            .filterNot { it in STOP }
            .toSet()

    private fun repeatedLoop(text: String, words: List<String>): String? {
        val sentences = text.split(SENTENCE_END)
            .map { NON_WORD.replace(it.lowercase(), "").trim().replace(WHITESPACE, " ") }
            .filter { it.split(" ").size >= 4 }
        if (sentences.groupingBy { it }.eachCount().any { it.value >= 2 }) return "it repeats a sentence"

        val normal = words.map { NON_WORD.replace(it.lowercase(), "") }.filter { it.isNotEmpty() }
        val grams = HashMap<String, Int>()
        for (i in 0..normal.size - REPEATED_PHRASE_WORDS) {
            val gram = normal.subList(i, i + REPEATED_PHRASE_WORDS).joinToString(" ")
            val count = (grams[gram] ?: 0) + 1
            if (count >= REPEATED_PHRASE_LIMIT) return "it repeats the same phrase"
            grams[gram] = count
        }
        return null
    }
}

/**
 * Asks the model for an explanation, validates the answer, retries at most [maxRetries] times with
 * the rejection reason in the prompt, then falls back to the bundled key points. The topic, its
 * facts and its level are input only: the model cannot change what is being taught.
 */
class ExplanationGenerator(private val maxRetries: Int = 2) {
    /**
     * @param model runs one generation and returns its full text, or is null when no model is
     *   available (then the bundled key points are returned right away). `attempt` starts at 1.
     * @param validate decides whether an answer is accepted; replaceable so a failure can be forced in tests.
     */
    suspend fun generate(
        input: ExplanationInput,
        model: (suspend (prompt: String, attempt: Int) -> String)?,
        validate: (String) -> ExplanationValidation = { ExplanationValidator.validate(it, input.keyPoints) },
    ): GeneratedExplanation {
        val bundled = BundledExplanation.build(input.topicName, input.keyPoints)
        if (model == null) return GeneratedExplanation(bundled, ContentSource.BUNDLED, modelCalls = 0, failures = emptyList())
        val failures = ArrayList<String>()
        for (attempt in 1..1 + maxRetries) {
            val prompt = ExplanationPrompt.build(input, retryReason = failures.lastOrNull())
            val output = try {
                model(prompt, attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures += "generation failed: ${e.message ?: e.javaClass.simpleName}"
                continue
            }
            when (val result = validate(output)) {
                is ExplanationValidation.Valid -> return GeneratedExplanation(result.text, ContentSource.AI, attempt, failures)
                is ExplanationValidation.Invalid -> failures += result.reason
            }
        }
        return GeneratedExplanation(bundled, ContentSource.BUNDLED, modelCalls = 1 + maxRetries, failures = failures)
    }
}
