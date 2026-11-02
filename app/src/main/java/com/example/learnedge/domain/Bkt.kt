package com.example.learnedge.domain

/**
 * Bayesian Knowledge Tracing parameters for one topic.
 * [learn] = P(T), [slip] = P(S), [guess] = P(G).
 */
data class BktParams(
    val learn: Double = 0.15,
    val slip: Double = 0.10,
    val guess: Double = 0.20,
) {
    init {
        require(learn in 0.0..1.0) { "P(T) must be in [0, 1], was $learn" }
        require(slip in 0.0..<1.0) { "P(S) must be in [0, 1), was $slip" }
        require(guess in 0.0..<1.0) { "P(G) must be in [0, 1), was $guess" }
    }
}

/** Standard BKT update. Pure and stateless so the same rule serves the diagnostic quiz and normal quizzes. */
object Bkt {
    const val MASTERY_THRESHOLD = 0.95

    /** Returns the new P(L) after observing one answer, given the current [pL]. */
    fun update(pL: Double, correct: Boolean, params: BktParams = BktParams()): Double {
        require(pL in 0.0..1.0) { "P(L) must be in [0, 1], was $pL" }
        val (learn, slip, guess) = params
        // Posterior that the topic was already known, given the observed answer.
        val (known, unknown) = if (correct) {
            pL * (1 - slip) to (1 - pL) * guess
        } else {
            pL * slip to (1 - pL) * (1 - guess)
        }
        // 0/0 only happens when the observation is impossible under both hypotheses; keep the prior.
        val posterior = if (known + unknown == 0.0) pL else known / (known + unknown)
        // Chance to learn the topic during this practice opportunity.
        return (posterior + (1 - posterior) * learn).coerceIn(0.0, 1.0)
    }

    /** Applies [update] for each answer in order. */
    fun updateAll(pL: Double, answers: List<Boolean>, params: BktParams = BktParams()): Double =
        answers.fold(pL) { p, correct -> update(p, correct, params) }

    fun isMastered(pL: Double): Boolean = pL >= MASTERY_THRESHOLD
}
