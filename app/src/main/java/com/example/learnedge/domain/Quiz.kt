package com.example.learnedge.domain

/** One answered quiz question and what it did to the topic's mastery P(L). */
data class QuizAttempt(
    val questionId: String,
    val topicId: String,
    val correct: Boolean,
    val timestamp: Long,
    val pLBefore: Double,
    val pLAfter: Double,
)

/** Chooses the questions of one quiz session from the trusted bank. */
object QuizSelector {
    const val MIN_QUESTIONS = 3
    const val MAX_QUESTIONS = 5

    /** A quiz has 4 questions by default, so a bank of 8 gives two completely different quizzes in a row. */
    const val DEFAULT_QUESTIONS = 4

    /**
     * Picks up to [count] questions (3-5 when the bank has that many): questions the learner has
     * never seen first, then the ones seen longest ago; among equally recent ones the choice is
     * random, so the quizzes differ from one session to the next. [hidden] (reported) questions are
     * never used. The questions are asked from easiest to hardest, and each question's options are
     * shuffled, with [BankQuestion.answerIndex] following the correct option.
     *
     * @param lastSeen epoch milliseconds the learner last answered each question id.
     */
    fun select(
        bank: List<BankQuestion>,
        lastSeen: Map<String, Long>,
        hidden: Set<String>,
        count: Int = DEFAULT_QUESTIONS,
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): List<BankQuestion> {
        val n = count.coerceIn(MIN_QUESTIONS, MAX_QUESTIONS)
        // Shuffle first, then sort stably: ties keep their random order.
        return bank.filter { it.id !in hidden }
            .shuffled(random)
            .sortedBy { lastSeen[it.id] ?: Long.MIN_VALUE }
            .take(n)
            .sortedBy { it.difficulty }
            .map { it.shuffled(random) }
    }
}

/** Recomputes mastery from a topic's quiz history, so a reported question can be taken out of it. */
object AttemptHistory {
    /**
     * Replays [attempts] (oldest first) from [startPL], the mastery before the first of them, with
     * one BKT update per attempt. Attempts of [reported] questions are skipped: they leave P(L)
     * unchanged, as if the question had never been asked. Returns the attempts with their
     * before/after values rewritten so later reports chain correctly.
     *
     * Limit: only quiz attempts are replayed. A mastery change from another source after the first
     * replayed attempt (for example "mark done") is not part of the history and is replaced.
     */
    fun replay(
        startPL: Double,
        attempts: List<QuizAttempt>,
        reported: Set<String>,
        params: BktParams = BktParams(),
    ): List<QuizAttempt> {
        var pL = startPL
        return attempts.map { attempt ->
            val before = pL
            if (attempt.questionId !in reported) pL = Bkt.update(pL, attempt.correct, params)
            attempt.copy(pLBefore = before, pLAfter = pL)
        }
    }
}
