package com.example.learnedge.domain

/**
 * A skill the learner wants to reach [targetLevel] (0–10) in. [weight] is w in the planner's
 * gap formula g = w * max(0, target - current) / 10.
 */
data class Skill(
    val id: String,
    val name: String,
    val targetLevel: Int,
    val weight: Double,
) {
    init {
        require(id.isNotBlank()) { "Skill id must not be blank" }
        require(targetLevel in 0..10) { "Skill $id: targetLevel must be in 0..10, was $targetLevel" }
        require(weight > 0) { "Skill $id: weight must be positive, was $weight" }
    }
}

/** One node of the knowledge graph. [estimatedMinutes] is the study time the scheduler budgets for it. */
data class Topic(
    val id: String,
    val name: String,
    val skillId: String,
    val estimatedMinutes: Int,
    val description: String = "",
    /** 3-5 short factual statements: the ground truth lessons are built from. */
    val keyPoints: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Topic id must not be blank" }
        require(estimatedMinutes > 0) { "Topic $id: estimatedMinutes must be positive, was $estimatedMinutes" }
    }
}

/** A learning goal, e.g. "Java Backend Developer": its skills and the topic graph that teaches them. */
class Track(
    val id: String,
    val name: String,
    val skills: List<Skill>,
    val graph: KnowledgeGraph,
) {
    private val skillsById = skills.associateBy { it.id }

    init {
        require(skillsById.size == skills.size) { "Track $id has duplicate skill ids" }
        for (topic in graph.topics) {
            require(topic.skillId in skillsById) { "Topic ${topic.id} in track $id has unknown skill ${topic.skillId}" }
        }
        for (skill in skills) {
            require(graph.topics.any { it.skillId == skill.id }) { "Skill ${skill.id} in track $id has no topics" }
        }
    }

    fun skill(skillId: String): Skill = skillsById[skillId] ?: throw NoSuchElementException("Unknown skill $skillId")

    fun topicsOf(skillId: String): List<Topic> = graph.topics.filter { it.skillId == skillId }
}

/** The learner's state for one topic: mastery P(L) and quiz history. */
data class TopicProgress(
    val topicId: String,
    val mastery: Double,
    val attempts: Int = 0,
    val correct: Int = 0,
) {
    init {
        require(mastery in 0.0..1.0) { "Mastery must be in [0, 1], was $mastery" }
        require(correct in 0..attempts) { "correct must be in 0..attempts" }
    }

    val isMastered: Boolean get() = Bkt.isMastered(mastery)
}
