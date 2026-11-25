package com.example.learnedge.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** What a [GeneratedContentEntity] row contains. Only EXPLANATION is written so far. */
enum class ContentKind { EXPLANATION, QUESTION, TIP }

/**
 * Text the on-device model wrote for a topic, kept with how it was made so it can be audited and
 * invalidated: [promptVersion] and [modelId] say what produced it, [levelBand] the learner level it
 * was pitched at, and [verified] that it passed validation. Rows are only ever added, never edited:
 * the newest verified row wins.
 */
@Entity(tableName = "generated_content", indices = [Index(value = ["topicId", "kind", "levelBand"])])
data class GeneratedContentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topicId: String,
    /** [ContentKind] name. */
    val kind: String,
    val payload: String,
    /** "AI" or "BUNDLED" (see ContentSource). */
    val source: String,
    /** LevelBand name the text was written for. */
    val levelBand: String,
    val promptVersion: Int,
    val modelId: String,
    val verified: Boolean,
    val createdAt: Long,
)

@Dao
interface GeneratedContentDao {
    @Query(
        "SELECT * FROM generated_content WHERE topicId = :topicId AND kind = :kind AND levelBand = :levelBand " +
            "AND promptVersion = :promptVersion AND verified = 1 AND source = 'AI' ORDER BY createdAt DESC, id DESC LIMIT 1",
    )
    fun observeLatestVerifiedAi(topicId: String, kind: String, levelBand: String, promptVersion: Int): Flow<GeneratedContentEntity?>

    @Query(
        "SELECT * FROM generated_content WHERE topicId = :topicId AND kind = :kind AND levelBand = :levelBand " +
            "AND promptVersion = :promptVersion AND verified = 1 AND source = 'AI' ORDER BY createdAt DESC, id DESC LIMIT 1",
    )
    suspend fun latestVerifiedAi(topicId: String, kind: String, levelBand: String, promptVersion: Int): GeneratedContentEntity?

    /** Every valid AI row of one kind written by the current prompt version, for the preparation planner and the "ready" count. */
    @Query("SELECT * FROM generated_content WHERE kind = :kind AND promptVersion = :promptVersion AND verified = 1 AND source = 'AI'")
    suspend fun verifiedAiRows(kind: String, promptVersion: Int): List<GeneratedContentEntity>

    @Query("SELECT * FROM generated_content WHERE kind = :kind AND promptVersion = :promptVersion AND verified = 1 AND source = 'AI'")
    fun observeVerifiedAiRows(kind: String, promptVersion: Int): Flow<List<GeneratedContentEntity>>

    @Insert suspend fun insert(row: GeneratedContentEntity): Long
}
