package com.example.learnedge.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val name: String,
    val position: Int,
)

@Entity(
    tableName = "skills",
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"])],
    indices = [Index("trackId")],
)
data class SkillEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val name: String,
    val targetLevel: Int,
    val weight: Double,
    val position: Int,
)

@Entity(
    tableName = "topics",
    foreignKeys = [
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"]),
        ForeignKey(entity = SkillEntity::class, parentColumns = ["id"], childColumns = ["skillId"]),
    ],
    indices = [Index("trackId"), Index("skillId")],
)
data class TopicEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val skillId: String,
    val name: String,
    val estimatedMinutes: Int,
    val description: String,
    /** Order in curriculum.json; the planner uses it to break priority ties. */
    val position: Int,
)

/** Edge of the knowledge graph: [prerequisiteId] must be learned before [topicId]. */
@Entity(
    tableName = "prerequisites",
    primaryKeys = ["topicId", "prerequisiteId"],
    foreignKeys = [
        ForeignKey(entity = TopicEntity::class, parentColumns = ["id"], childColumns = ["topicId"]),
        ForeignKey(entity = TopicEntity::class, parentColumns = ["id"], childColumns = ["prerequisiteId"]),
    ],
    indices = [Index("prerequisiteId")],
)
data class PrerequisiteEntity(
    val topicId: String,
    val prerequisiteId: String,
)

/** The learner's mastery of one topic. Rows exist only once the learner has been assessed. */
@Entity(
    tableName = "learner_topic_state",
    foreignKeys = [ForeignKey(entity = TopicEntity::class, parentColumns = ["id"], childColumns = ["topicId"])],
)
data class LearnerTopicStateEntity(
    @PrimaryKey val topicId: String,
    val pL: Double,
    val attempts: Int,
    val correct: Int,
    /** Epoch milliseconds of the last change. */
    val lastUpdated: Long,
)
