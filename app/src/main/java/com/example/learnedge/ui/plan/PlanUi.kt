package com.example.learnedge.ui.plan

import com.example.learnedge.data.db.PlanItemEntity
import com.example.learnedge.domain.TopicProgress
import com.example.learnedge.domain.Track

/** A plan item ready for display, shared by the Today and Plan screens. */
data class StudyItemUi(
    val id: Long,
    val topicName: String,
    val skillName: String,
    val description: String,
    val minutes: Int,
    val part: Int,
    val parts: Int,
    val done: Boolean,
    /** Current P(L) of the topic, or null if never assessed. */
    val mastery: Double?,
    val deferredReason: String?,
)

fun PlanItemEntity.toUi(track: Track, mastery: Map<String, TopicProgress>): StudyItemUi {
    val topic = if (topicId in track.graph) track.graph.topic(topicId) else null
    return StudyItemUi(
        id = id,
        topicName = topic?.name ?: topicId,
        skillName = topic?.let { track.skill(it.skillId).name }.orEmpty(),
        description = topic?.description.orEmpty(),
        minutes = minutes,
        part = part,
        parts = parts,
        done = done,
        mastery = mastery[topicId]?.mastery,
        deferredReason = deferredReason,
    )
}

fun StudyItemUi.detailLine(): String = buildString {
    append("$minutes min")
    if (parts > 1) append(" · part $part of $parts")
    if (skillName.isNotEmpty()) append(" · $skillName")
    mastery?.let { append(" · mastery ${(it * 100).toInt()}%") }
}
