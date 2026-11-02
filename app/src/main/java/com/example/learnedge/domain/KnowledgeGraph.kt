package com.example.learnedge.domain

/**
 * One node of the knowledge graph. [weight] is the topic's importance (w in the planner's gap
 * formula) and [estimatedMinutes] is the study time the scheduler budgets for it.
 */
data class Topic(
    val id: String,
    val name: String,
    val weight: Double = 1.0,
    val estimatedMinutes: Int = 30,
) {
    init {
        require(id.isNotBlank()) { "Topic id must not be blank" }
        require(weight > 0) { "Topic $id: weight must be positive, was $weight" }
        require(estimatedMinutes > 0) { "Topic $id: estimatedMinutes must be positive, was $estimatedMinutes" }
    }
}

/** The student's state for one topic: quiz history and current mastery P(L). */
data class TopicProgress(
    val topicId: String,
    val mastery: Double,
    val correctAnswers: Int = 0,
    val totalAnswers: Int = 0,
) {
    init {
        require(mastery in 0.0..1.0) { "Mastery must be in [0, 1], was $mastery" }
        require(correctAnswers in 0..totalAnswers) { "correctAnswers must be in 0..totalAnswers" }
    }

    val isMastered: Boolean get() = Bkt.isMastered(mastery)
}

/**
 * Topics (nodes) and prerequisite edges. [prerequisites] maps a topic id to the ids that must be
 * learned before it. Construction fails on unknown ids, self-loops or cycles, so every graph
 * that exists can be ordered by the planner.
 */
class KnowledgeGraph(
    topics: List<Topic>,
    val prerequisites: Map<String, Set<String>>,
) {
    val topics: Map<String, Topic> = topics.associateBy { it.id }

    init {
        require(this.topics.size == topics.size) { "Duplicate topic ids" }
        for ((topic, prereqs) in prerequisites) {
            require(topic in this.topics) { "Unknown topic in prerequisites: $topic" }
            for (p in prereqs) {
                require(p in this.topics) { "Unknown prerequisite $p of $topic" }
                require(p != topic) { "Topic $topic lists itself as a prerequisite" }
            }
        }
        require(!hasCycle()) { "Prerequisite graph has a cycle" }
    }

    fun prerequisitesOf(topicId: String): Set<String> = prerequisites[topicId].orEmpty()

    /** Topics that list [topicId] as a prerequisite. */
    fun dependentsOf(topicId: String): Set<String> =
        prerequisites.filterValues { topicId in it }.keys

    private fun hasCycle(): Boolean {
        // Kahn's algorithm: if some topics never reach in-degree 0, they sit on a cycle.
        val inDegree = topics.keys.associateWith { prerequisitesOf(it).size }.toMutableMap()
        val ready = ArrayDeque(inDegree.filterValues { it == 0 }.keys)
        var visited = 0
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            visited++
            for (dependent in dependentsOf(id)) {
                val left = inDegree.getValue(dependent) - 1
                inDegree[dependent] = left
                if (left == 0) ready.addLast(dependent)
            }
        }
        return visited != topics.size
    }
}
