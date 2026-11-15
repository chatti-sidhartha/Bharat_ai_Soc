package com.example.learnedge.data.content

import android.content.res.AssetManager
import com.example.learnedge.domain.BankQuestion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Reads assets/question_bank.json. No Android dependencies, so it is unit-tested on the JVM. */
object QuestionBankParser {
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(text: String): List<BankQuestion> {
        val questions = json.decodeFromString<BankFile>(text).questions.map {
            BankQuestion(it.id, it.topicId, it.question, it.options, it.answerIndex, it.explanation, it.difficulty)
        }
        val duplicates = questions.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate question ids in question_bank.json: $duplicates" }
        return questions
    }
}

@Serializable
private data class BankFile(val questions: List<BankQuestionJson>)

@Serializable
private data class BankQuestionJson(
    val id: String,
    val topicId: String,
    val question: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String,
    val difficulty: Int,
)

/**
 * The trusted, hand-written question bank. Mastery is only ever updated from these questions;
 * the LLM never generates quiz questions.
 */
class QuestionBankRepository(private val assets: AssetManager) {
    @Volatile private var cached: Map<String, List<BankQuestion>>? = null

    suspend fun questions(topicId: String): List<BankQuestion> = byTopic()[topicId].orEmpty()

    suspend fun count(topicId: String): Int = questions(topicId).size

    private suspend fun byTopic(): Map<String, List<BankQuestion>> = cached ?: withContext(Dispatchers.IO) {
        QuestionBankParser.parse(assets.open(ASSET).bufferedReader().use { it.readText() }).groupBy { it.topicId }
    }.also { cached = it }

    private companion object {
        const val ASSET = "question_bank.json"
    }
}
