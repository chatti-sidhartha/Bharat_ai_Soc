package com.example.learnedge.domain

/** A hand-written multiple-choice question about one topic, from the bundled question bank. */
data class BankQuestion(
    val id: String,
    val topicId: String,
    val question: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String,
) {
    init {
        require(question.isNotBlank()) { "Question $id has no text" }
        require(options.size >= 2) { "Question $id needs at least two options" }
        require(options.toSet().size == options.size) { "Question $id has duplicate options" }
        require(answerIndex in options.indices) { "Question $id: answerIndex $answerIndex is not an option" }
    }

    fun isCorrect(choice: Int): Boolean = choice == answerIndex
}

/**
 * Hand-written teaching content for one topic: short key points (always true, always available
 * offline) and the questions a later quiz draws from. The LLM may only rephrase this, never replace it.
 */
data class TopicContent(
    val topicId: String,
    val keyPoints: List<String>,
    val questions: List<BankQuestion>,
) {
    init {
        require(keyPoints.isNotEmpty() && keyPoints.all { it.isNotBlank() }) { "Topic $topicId needs non-blank key points" }
        require(questions.all { it.topicId == topicId }) { "Topic $topicId has a question tagged with another topic" }
    }
}
