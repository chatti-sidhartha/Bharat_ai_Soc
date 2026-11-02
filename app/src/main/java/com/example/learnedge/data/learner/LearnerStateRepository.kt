package com.example.learnedge.data.learner

import com.example.learnedge.data.db.AppDatabase
import com.example.learnedge.data.db.LearnerTopicStateEntity
import com.example.learnedge.domain.TopicProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Mastery P(L) and quiz counts per topic (table learner_topic_state). */
class LearnerStateRepository(db: AppDatabase) {
    private val dao = db.learnerStateDao()

    fun observe(): Flow<Map<String, TopicProgress>> = dao.observeAll().map { rows -> rows.toDomain() }

    suspend fun get(): Map<String, TopicProgress> = dao.getAll().toDomain()

    suspend fun save(progress: Collection<TopicProgress>) {
        val now = System.currentTimeMillis()
        dao.upsert(progress.map { LearnerTopicStateEntity(it.topicId, it.mastery, it.attempts, it.correct, now) })
    }

    private fun List<LearnerTopicStateEntity>.toDomain(): Map<String, TopicProgress> =
        associate { it.topicId to TopicProgress(it.topicId, it.pL, it.attempts, it.correct) }
}
