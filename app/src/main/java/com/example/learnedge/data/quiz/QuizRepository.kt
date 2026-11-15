package com.example.learnedge.data.quiz

import androidx.room.withTransaction
import com.example.learnedge.data.content.QuestionBankRepository
import com.example.learnedge.data.db.AppDatabase
import com.example.learnedge.data.db.QuestionAttemptEntity
import com.example.learnedge.data.db.ReportedQuestionEntity
import com.example.learnedge.data.learner.LearnerStateRepository
import com.example.learnedge.data.plan.PlanRepository
import com.example.learnedge.domain.AttemptHistory
import com.example.learnedge.domain.BankQuestion
import com.example.learnedge.domain.Bkt
import com.example.learnedge.domain.QuizAttempt
import com.example.learnedge.domain.QuizSelector
import com.example.learnedge.domain.TopicProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The questions of one quiz and the topic's mastery when it started. */
data class QuizSession(val topicId: String, val questions: List<BankQuestion>, val startMastery: Double)

data class AnswerResult(val correct: Boolean, val pLBefore: Double, val pLAfter: Double) {
    val mastered: Boolean get() = Bkt.isMastered(pLAfter)
}

/**
 * Closes the adaptive loop: a quiz answer updates the topic's mastery with one BKT step (the locked
 * parameters) and is stored; finishing a quiz rebuilds the plan from tomorrow. Questions come only
 * from the trusted bank; the LLM never writes or scores them.
 */
class QuizRepository(
    private val db: AppDatabase,
    private val bank: QuestionBankRepository,
    private val learnerState: LearnerStateRepository,
    private val plans: PlanRepository,
) {
    private val dao = db.quizDao()

    /** Up to five bank questions for [topicId], unseen or least recently seen first, reported ones never. */
    suspend fun startSession(topicId: String): QuizSession {
        val hidden = dao.reportedIds().toSet()
        val lastSeen = dao.lastSeen(topicId).associate { it.questionId to it.lastSeen }
        return QuizSession(topicId, QuizSelector.select(bank.questions(topicId), lastSeen, hidden), currentMastery(topicId))
    }

    suspend fun currentMastery(topicId: String): Double = learnerState.get()[topicId]?.mastery ?: 0.0

    /** Every stored attempt, oldest first (for the mastery-over-time chart). */
    fun observeAttempts(): Flow<List<QuizAttempt>> = dao.observeAllAttempts().map { rows ->
        rows.map { QuizAttempt(it.questionId, it.topicId, it.correct, it.timestamp, it.pLBefore, it.pLAfter) }
    }

    fun observeReported(): Flow<Set<String>> = dao.observeReportedIds().map { it.toSet() }

    /** Applies one BKT update for the answer and stores the attempt, atomically. */
    suspend fun answer(question: BankQuestion, chosenIndex: Int): AnswerResult = db.withTransaction {
        val current = learnerState.get()[question.topicId] ?: TopicProgress(question.topicId, 0.0)
        val correct = question.isCorrect(chosenIndex)
        val after = Bkt.update(current.mastery, correct)
        learnerState.save(
            listOf(current.copy(mastery = after, attempts = current.attempts + 1, correct = current.correct + if (correct) 1 else 0)),
        )
        dao.insertAttempt(
            QuestionAttemptEntity(
                questionId = question.id,
                topicId = question.topicId,
                chosenIndex = chosenIndex,
                correct = correct,
                timestamp = System.currentTimeMillis(),
                pLBefore = current.mastery,
                pLAfter = after,
            ),
        )
        AnswerResult(correct, current.mastery, after)
    }

    /**
     * Hides [questionId] from future quizzes and removes its effect on mastery by replaying the
     * topic's quiz history from the mastery before that question's first attempt, without it.
     * Returns the topic's mastery afterwards, or null if the question was never answered.
     *
     * This is the least invasive correction: earlier attempts are untouched, later ones are re-chained.
     * Mastery changes from other sources after that point (for example "mark done") are not replayed.
     */
    suspend fun report(questionId: String): Double? = db.withTransaction {
        val alreadyReported = questionId in dao.reportedIds()
        dao.insertReported(ReportedQuestionEntity(questionId, System.currentTimeMillis()))
        val first = dao.firstAttemptOf(questionId) ?: return@withTransaction null
        val topicId = first.topicId
        if (alreadyReported) return@withTransaction currentMastery(topicId)

        val attempts = dao.attemptsOf(topicId)
        val affected = attempts.dropWhile { it.id != first.id }
        val replayed = AttemptHistory.replay(
            startPL = first.pLBefore,
            attempts = affected.map { QuizAttempt(it.questionId, it.topicId, it.correct, it.timestamp, it.pLBefore, it.pLAfter) },
            reported = dao.reportedIds().toSet(),
        )
        dao.updateAttempts(affected.zip(replayed) { entity, new -> entity.copy(pLBefore = new.pLBefore, pLAfter = new.pLAfter) })

        val removed = attempts.filter { it.questionId == questionId }
        val current = learnerState.get()[topicId] ?: TopicProgress(topicId, first.pLBefore)
        val attemptCount = (current.attempts - removed.size).coerceAtLeast(0)
        val correctCount = (current.correct - removed.count { it.correct }).coerceIn(0, attemptCount)
        val restored = replayed.last().pLAfter
        learnerState.save(listOf(TopicProgress(topicId, restored, attemptCount, correctCount)))
        restored
    }

    /** Rebuilds the plan from tomorrow, the same way marking a topic done does. */
    suspend fun finish() = plans.regenerate(PlanRepository.Mode.FROM_TOMORROW)
}
