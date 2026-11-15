package com.example.learnedge.domain

/**
 * A hand-written multiple-choice question about one topic, from the bundled question bank. The
 * bank is the trusted source for mastery updates; the LLM never writes or changes these questions.
 */
data class BankQuestion(
    val id: String,
    val topicId: String,
    val question: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String,
    /** 1 = recall, 2 = understanding, 3 = application. */
    val difficulty: Int,
) {
    init {
        require(question.isNotBlank()) { "Question $id has no text" }
        require(options.size >= 2) { "Question $id needs at least two options" }
        require(options.toSet().size == options.size) { "Question $id has duplicate options" }
        require(answerIndex in options.indices) { "Question $id: answerIndex $answerIndex is not an option" }
        require(difficulty in 1..3) { "Question $id: difficulty must be 1..3, was $difficulty" }
    }

    fun isCorrect(choice: Int): Boolean = choice == answerIndex
}

/** Where a piece of text shown to the learner came from. */
enum class ContentSource { AI, BUNDLED }

/**
 * How an explanation is pitched, chosen from the learner's mastery P(L) of the topic:
 * below 0.3 beginner, below 0.7 intermediate, otherwise revision.
 */
enum class LevelBand {
    BEGINNER, INTERMEDIATE, REVISION;

    companion object {
        const val BEGINNER_BELOW = 0.3
        const val INTERMEDIATE_BELOW = 0.7

        fun of(mastery: Double): LevelBand = when {
            mastery < BEGINNER_BELOW -> BEGINNER
            mastery < INTERMEDIATE_BELOW -> INTERMEDIATE
            else -> REVISION
        }
    }
}
