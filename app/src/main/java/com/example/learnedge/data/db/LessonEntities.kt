package com.example.learnedge.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A lesson the on-device model wrote and that passed validation. Only AI lessons are stored:
 * the bundled fallback text is rebuilt from assets, so it never goes stale or needs a migration.
 */
@Entity(tableName = "topic_lessons")
data class TopicLessonEntity(
    @PrimaryKey val topicId: String,
    val explanation: String,
    val example: String,
    val createdAt: Long,
)

@Dao
interface LessonDao {
    @Query("SELECT * FROM topic_lessons WHERE topicId = :topicId")
    fun observe(topicId: String): Flow<TopicLessonEntity?>

    @Query("SELECT * FROM topic_lessons WHERE topicId = :topicId")
    suspend fun get(topicId: String): TopicLessonEntity?

    @Upsert suspend fun upsert(lesson: TopicLessonEntity)
}
