package com.example.learnedge.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update

/**
 * One answered quiz question. [pLBefore] and [pLAfter] are the topic's mastery around the single
 * BKT update this answer caused; they are rewritten when an earlier question is reported, so the
 * chain stays consistent.
 */
@Entity(tableName = "question_attempts", indices = [Index("topicId"), Index("questionId")])
data class QuestionAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val questionId: String,
    val topicId: String,
    val chosenIndex: Int,
    val correct: Boolean,
    /** Epoch milliseconds. */
    val timestamp: Long,
    val pLBefore: Double,
    val pLAfter: Double,
)

/** A bank question the learner reported: it is hidden from future quizzes and its attempts no longer count. */
@Entity(tableName = "reported_questions")
data class ReportedQuestionEntity(
    @PrimaryKey val questionId: String,
    val reportedAt: Long,
)

data class LastSeenRow(val questionId: String, val lastSeen: Long)

@Dao
interface QuizDao {
    @Insert suspend fun insertAttempt(attempt: QuestionAttemptEntity): Long

    @Update suspend fun updateAttempts(attempts: List<QuestionAttemptEntity>)

    /** The topic's attempts, oldest first. */
    @Query("SELECT * FROM question_attempts WHERE topicId = :topicId ORDER BY timestamp, id")
    suspend fun attemptsOf(topicId: String): List<QuestionAttemptEntity>

    @Query("SELECT * FROM question_attempts WHERE questionId = :questionId ORDER BY timestamp, id LIMIT 1")
    suspend fun firstAttemptOf(questionId: String): QuestionAttemptEntity?

    @Query("SELECT questionId, MAX(timestamp) AS lastSeen FROM question_attempts WHERE topicId = :topicId GROUP BY questionId")
    suspend fun lastSeen(topicId: String): List<LastSeenRow>

    @Query("SELECT questionId FROM reported_questions")
    suspend fun reportedIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertReported(reported: ReportedQuestionEntity)
}
