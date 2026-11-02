package com.example.learnedge.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The single learner of this phone. There is at most one row, with [id] = 1. */
@Entity(
    tableName = "learner_profile",
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"])],
)
data class LearnerProfileEntity(
    @PrimaryKey val id: Int = 1,
    val trackId: String,
    val interests: String,
    val dailyMinutes: Int,
    val deadlineEpochDay: Long,
    val diagnosticCompleted: Boolean,
    /** Epoch milliseconds of the last change. */
    val updatedAt: Long,
)

/** Self-rated level (0–10) for one skill, entered at onboarding. */
@Entity(
    tableName = "skill_ratings",
    foreignKeys = [ForeignKey(entity = SkillEntity::class, parentColumns = ["id"], childColumns = ["skillId"])],
)
data class SkillRatingEntity(
    @PrimaryKey val skillId: String,
    val rating: Int,
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM learner_profile WHERE id = 1")
    fun observeProfile(): Flow<LearnerProfileEntity?>

    @Query("SELECT * FROM learner_profile WHERE id = 1")
    suspend fun getProfile(): LearnerProfileEntity?

    @Query("SELECT * FROM skill_ratings")
    fun observeRatings(): Flow<List<SkillRatingEntity>>

    @Query("SELECT * FROM skill_ratings")
    suspend fun getRatings(): List<SkillRatingEntity>

    @Upsert suspend fun upsertProfile(profile: LearnerProfileEntity)

    @Query("DELETE FROM skill_ratings") suspend fun deleteRatings()

    @Upsert suspend fun upsertRatings(ratings: List<SkillRatingEntity>)

    @Transaction
    suspend fun save(profile: LearnerProfileEntity, ratings: List<SkillRatingEntity>) {
        upsertProfile(profile)
        deleteRatings()
        upsertRatings(ratings)
    }

    @Query("UPDATE learner_profile SET diagnosticCompleted = :completed, updatedAt = :now WHERE id = 1")
    suspend fun setDiagnosticCompleted(completed: Boolean, now: Long)
}
