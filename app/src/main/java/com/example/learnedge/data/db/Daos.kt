package com.example.learnedge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CurriculumDao {
    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun trackCount(): Int

    @Insert suspend fun insertTracks(tracks: List<TrackEntity>)
    @Insert suspend fun insertSkills(skills: List<SkillEntity>)
    @Insert suspend fun insertTopics(topics: List<TopicEntity>)
    @Insert suspend fun insertPrerequisites(edges: List<PrerequisiteEntity>)

    @Query("SELECT * FROM tracks ORDER BY position")
    suspend fun tracks(): List<TrackEntity>

    @Query("SELECT * FROM skills WHERE trackId = :trackId ORDER BY position")
    suspend fun skills(trackId: String): List<SkillEntity>

    @Query("SELECT * FROM topics WHERE trackId = :trackId ORDER BY position")
    suspend fun topics(trackId: String): List<TopicEntity>

    @Query("SELECT p.* FROM prerequisites p JOIN topics t ON p.topicId = t.id WHERE t.trackId = :trackId")
    suspend fun prerequisites(trackId: String): List<PrerequisiteEntity>
}

@Dao
interface LearnerStateDao {
    @Query("SELECT * FROM learner_topic_state")
    fun observeAll(): Flow<List<LearnerTopicStateEntity>>

    @Query("SELECT * FROM learner_topic_state")
    suspend fun getAll(): List<LearnerTopicStateEntity>

    @Query("SELECT * FROM learner_topic_state WHERE topicId = :topicId")
    suspend fun get(topicId: String): LearnerTopicStateEntity?

    @Upsert
    suspend fun upsert(states: List<LearnerTopicStateEntity>)
}
