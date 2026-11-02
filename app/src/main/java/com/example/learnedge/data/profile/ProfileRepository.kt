package com.example.learnedge.data.profile

import com.example.learnedge.data.db.AppDatabase
import com.example.learnedge.data.db.LearnerProfileEntity
import com.example.learnedge.data.db.SkillRatingEntity
import com.example.learnedge.domain.LearnerProfile
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class ProfileRepository(db: AppDatabase) {
    private val dao = db.profileDao()

    fun observe(): Flow<LearnerProfile?> =
        combine(dao.observeProfile(), dao.observeRatings()) { profile, ratings -> profile?.toDomain(ratings) }

    suspend fun get(): LearnerProfile? = dao.getProfile()?.toDomain(dao.getRatings())

    suspend fun save(profile: LearnerProfile) {
        dao.save(
            LearnerProfileEntity(
                trackId = profile.trackId,
                interests = profile.interests.trim(),
                dailyMinutes = profile.dailyMinutes,
                deadlineEpochDay = profile.deadline.toEpochDay(),
                diagnosticCompleted = profile.diagnosticCompleted,
                updatedAt = System.currentTimeMillis(),
            ),
            profile.selfRatings.map { (skillId, rating) -> SkillRatingEntity(skillId, rating) },
        )
    }

    suspend fun setDiagnosticCompleted(completed: Boolean) {
        dao.setDiagnosticCompleted(completed, System.currentTimeMillis())
    }

    private fun LearnerProfileEntity.toDomain(ratings: List<SkillRatingEntity>) = LearnerProfile(
        trackId = trackId,
        interests = interests,
        selfRatings = ratings.associate { it.skillId to it.rating },
        dailyMinutes = dailyMinutes,
        deadline = LocalDate.ofEpochDay(deadlineEpochDay),
        diagnosticCompleted = diagnosticCompleted,
    )
}
