package com.example.learnedge.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Text written for one day's plan: one row per topic plus one summary row with [topicId] = "".
 * [signature] identifies the day's topics the text was written for; a different plan means stale tips.
 */
@Entity(tableName = "daily_tips", primaryKeys = ["dayEpochDay", "topicId"])
data class DailyTipEntity(
    val dayEpochDay: Long,
    val topicId: String,
    val text: String,
    /** "AI" or "RULE_BASED". */
    val source: String,
    val signature: String,
    val createdAt: Long,
)

@Dao
interface TipDao {
    @Query("SELECT * FROM daily_tips WHERE dayEpochDay = :day")
    fun observeDay(day: Long): Flow<List<DailyTipEntity>>

    @Query("SELECT * FROM daily_tips WHERE dayEpochDay = :day")
    suspend fun getDay(day: Long): List<DailyTipEntity>

    @Query("DELETE FROM daily_tips WHERE dayEpochDay = :day")
    suspend fun deleteDay(day: Long)

    @Insert suspend fun insert(rows: List<DailyTipEntity>)

    @Transaction
    suspend fun replaceDay(day: Long, rows: List<DailyTipEntity>) {
        deleteDay(day)
        insert(rows)
    }
}
