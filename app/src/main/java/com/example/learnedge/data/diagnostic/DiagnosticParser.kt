package com.example.learnedge.data.diagnostic

import com.example.learnedge.domain.DiagnosticQuestion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Reads assets/diagnostic.json. No Android dependencies, so it is unit-tested on the JVM. */
object DiagnosticParser {
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(text: String): List<DiagnosticQuestion> {
        val questions = json.decodeFromString<DiagnosticFile>(text).questions.map {
            DiagnosticQuestion(it.id, it.skillId, it.topicId, it.question, it.options, it.answerIndex, it.explanation)
        }
        val duplicates = questions.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate question ids in diagnostic.json: $duplicates" }
        return questions
    }
}

@Serializable
private data class DiagnosticFile(val questions: List<QuestionJson>)

@Serializable
private data class QuestionJson(
    val id: String,
    val skillId: String,
    val topicId: String,
    val question: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String = "",
)
