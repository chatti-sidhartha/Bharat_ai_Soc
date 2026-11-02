package com.example.learnedge.domain

import java.util.PriorityQueue

/** A topic in the learning path with its priority p_v. */
data class PlannedTopic(val topic: Topic, val priority: Double)

/**
 * Orders the topics the learner still has to study. Deterministic: the same input always gives the
 * same path, so plans are reproducible and testable.
 */
object PathPlanner {
    /** p_v = g_skill(v) * (1 + DESCENDANT_BONUS * descendants(v)): foundations that unlock many topics go first. */
    const val DESCENDANT_BONUS = 0.1

    fun priority(track: Track, topic: Topic, gaps: Map<String, SkillGap>): Double =
        (gaps[topic.skillId]?.gap ?: 0.0) * (1 + DESCENDANT_BONUS * track.graph.descendantCount(topic.id))

    /**
     * Priority Kahn topological sort over the topics that are not mastered (P(L) < 0.95) and not in
     * [satisfied]. Mastered or satisfied prerequisites count as done. Among the topics whose
     * prerequisites are all done, the highest priority is taken next; ties keep curriculum order.
     */
    fun plan(
        track: Track,
        mastery: Map<String, Double>,
        gaps: Map<String, SkillGap>,
        satisfied: Set<String> = emptySet(),
    ): List<PlannedTopic> {
        val graph = track.graph
        val position = graph.topics.withIndex().associate { (i, t) -> t.id to i }
        val pending = graph.topics
            .filter { it.id !in satisfied && !Bkt.isMastered(mastery[it.id] ?: 0.0) }
            .associateBy { it.id }
        val priorities = pending.mapValues { (_, topic) -> priority(track, topic, gaps) }

        val inDegree = pending.keys.associateWith { id -> graph.getPrerequisites(id).count { it in pending } }.toMutableMap()
        val ready = PriorityQueue(
            compareByDescending<String> { priorities.getValue(it) }.thenBy { position.getValue(it) }
        )
        ready += inDegree.filterValues { it == 0 }.keys

        val path = ArrayList<PlannedTopic>(pending.size)
        while (ready.isNotEmpty()) {
            val id = ready.poll()!!
            path += PlannedTopic(pending.getValue(id), priorities.getValue(id))
            for (dependent in graph.getDependents(id)) {
                if (dependent !in pending) continue
                val left = inDegree.getValue(dependent) - 1
                inDegree[dependent] = left
                if (left == 0) ready += dependent
            }
        }
        // KnowledgeGraph is acyclic by construction, so every pending topic is reached.
        check(path.size == pending.size) { "Planner could not order all topics" }
        return path
    }
}
