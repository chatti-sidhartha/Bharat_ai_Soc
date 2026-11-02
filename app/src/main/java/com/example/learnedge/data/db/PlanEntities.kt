package com.example.learnedge.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The current study plan. There is at most one row, with [id] = 1; its items are in plan_items. */
@Entity(
    tableName = "study_plans",
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"])],
)
data class StudyPlanEntity(
    @PrimaryKey val id: Int = 1,
    val trackId: String,
    /** Day the plan was last regenerated; a new day triggers a fresh plan. */
    val generatedOnEpochDay: Long,
    val createdAt: Long,
    val deadlineEpochDay: Long,
    val dailyMinutes: Int,
    val totalMinutes: Int,
    val feasible: Boolean,
)

/**
 * One scheduled study block. [dayEpochDay] is null for a deferred topic, which then has a
 * [deferredReason]. Done items are history and survive regeneration.
 */
@Entity(
    tableName = "plan_items",
    foreignKeys = [ForeignKey(entity = TopicEntity::class, parentColumns = ["id"], childColumns = ["topicId"])],
    indices = [Index("topicId"), Index("dayEpochDay")],
)
data class PlanItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topicId: String,
    val dayEpochDay: Long?,
    val position: Int,
    val minutes: Int,
    val part: Int,
    val parts: Int,
    val priority: Double,
    val done: Boolean = false,
    val doneAt: Long? = null,
    /** Mastery before "done" gave its boost, so un-ticking can restore it. */
    val pLBeforeDone: Double? = null,
    val deferredReason: String? = null,
)

@Dao
interface PlanDao {
    @Query("SELECT * FROM study_plans WHERE id = 1")
    fun observePlan(): Flow<StudyPlanEntity?>

    @Query("SELECT * FROM study_plans WHERE id = 1")
    suspend fun getPlan(): StudyPlanEntity?

    @Query("SELECT * FROM plan_items ORDER BY dayEpochDay IS NULL, dayEpochDay, position")
    fun observeItems(): Flow<List<PlanItemEntity>>

    @Query("SELECT * FROM plan_items ORDER BY dayEpochDay IS NULL, dayEpochDay, position")
    suspend fun getItems(): List<PlanItemEntity>

    @Query("SELECT * FROM plan_items WHERE id = :id")
    suspend fun getItem(id: Long): PlanItemEntity?

    @Update suspend fun updateItem(item: PlanItemEntity)

    @Upsert suspend fun upsertPlan(plan: StudyPlanEntity)

    @Insert suspend fun insertItems(items: List<PlanItemEntity>)

    @Query("DELETE FROM plan_items WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<Long>)

    @Transaction
    suspend fun replace(plan: StudyPlanEntity, deleteIds: List<Long>, newItems: List<PlanItemEntity>) {
        if (deleteIds.isNotEmpty()) deleteItems(deleteIds)
        insertItems(newItems)
        upsertPlan(plan)
    }
}
