package com.example.learnedge.domain

/** A hand-written multiple-choice question that probes one skill, tagged with the topic it tests. */
data class DiagnosticQuestion(
    val id: String,
    val skillId: String,
    val topicId: String,
    val question: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String,
) {
    init {
        require(options.size >= 2) { "Question $id needs at least two options" }
        require(answerIndex in options.indices) { "Question $id: answerIndex $answerIndex is not an option" }
    }

    fun isCorrect(choice: Int): Boolean = choice == answerIndex
}

data class DiagnosticAnswer(
    val questionId: String,
    val skillId: String,
    val topicId: String,
    val correct: Boolean,
)

/** Starting mastery from the self-rating and the diagnostic quiz. */
object InitialMastery {
    /** A self-rating alone never claims more than 50% mastery; only quiz evidence can push higher. */
    const val MAX_PRIOR = 0.5

    fun prior(selfRating: Int): Double = (selfRating / 10.0 * 0.5).coerceIn(0.0, MAX_PRIOR)

    /**
     * For every topic of [track]: start from [prior] of its skill's self-rating, then apply BKT for
     * each diagnostic answer on that skill, in order. Attempts/correct count only the answers to
     * questions tagged with that exact topic.
     */
    fun compute(
        track: Track,
        selfRatings: Map<String, Int>,
        answers: List<DiagnosticAnswer>,
        params: BktParams = BktParams(),
    ): Map<String, TopicProgress> {
        val bySkill = answers.groupBy { it.skillId }
        return track.graph.topics.associate { topic ->
            val skillAnswers = bySkill[topic.skillId].orEmpty()
            val pL = Bkt.updateAll(prior(selfRatings[topic.skillId] ?: 0), skillAnswers.map { it.correct }, params)
            val own = skillAnswers.filter { it.topicId == topic.id }
            topic.id to TopicProgress(topic.id, pL, attempts = own.size, correct = own.count { it.correct })
        }
    }
}
