package com.example.learnedge.domain

/**
 * Topics (nodes) and prerequisite edges. [prerequisites] maps a topic id to the ids that must be
 * learned before it. Construction fails loudly on unknown ids, self-loops or cycles, so every
 * graph that exists can be ordered by the planner.
 */
class KnowledgeGraph(
    /** In curriculum order; the planner uses this order to break ties. */
    val topics: List<Topic>,
    prerequisites: Map<String, Set<String>>,
) {
    private val byId: Map<String, Topic> = topics.associateBy { it.id }
    private val prerequisites: Map<String, Set<String>> = topics.associate { it.id to prerequisites[it.id].orEmpty() }
    private val dependents: Map<String, Set<String>>

    init {
        require(byId.size == topics.size) {
            "Duplicate topic ids: ${topics.groupBy { it.id }.filterValues { it.size > 1 }.keys}"
        }
        for ((topic, prereqs) in prerequisites) {
            require(topic in byId) { "Unknown topic in prerequisites: $topic" }
            for (p in prereqs) {
                require(p in byId) { "Topic $topic has unknown prerequisite $p" }
                require(p != topic) { "Topic $topic lists itself as a prerequisite" }
            }
        }
        dependents = topics.associate { t -> t.id to topics.filter { t.id in this.prerequisites.getValue(it.id) }.map { it.id }.toSet() }
        val stuck = topicsOnOrAfterCycle()
        require(stuck.isEmpty()) { "Prerequisite cycle detected among topics: ${stuck.joinToString()}" }
    }

    private val descendants: Map<String, Set<String>> by lazy {
        val memo = HashMap<String, Set<String>>()
        fun collect(id: String): Set<String> = memo.getOrPut(id) {
            dependents.getValue(id).flatMapTo(HashSet()) { collect(it) + it }
        }
        topics.associate { it.id to collect(it.id) }
    }

    operator fun contains(topicId: String): Boolean = topicId in byId

    fun topic(topicId: String): Topic = byId[topicId] ?: throw NoSuchElementException("Unknown topic $topicId")

    /** Direct prerequisites of [topicId]. */
    fun getPrerequisites(topicId: String): Set<String> = prerequisites[topicId] ?: unknown(topicId)

    /** Topics that list [topicId] as a direct prerequisite. */
    fun getDependents(topicId: String): Set<String> = dependents[topicId] ?: unknown(topicId)

    /** Every topic that needs [topicId] directly or indirectly. */
    fun getDescendants(topicId: String): Set<String> = descendants[topicId] ?: unknown(topicId)

    /** How many topics depend on [topicId], directly or indirectly. Used to prioritise "foundation" topics. */
    fun descendantCount(topicId: String): Int = getDescendants(topicId).size

    private fun unknown(topicId: String): Nothing = throw NoSuchElementException("Unknown topic $topicId")

    /**
     * Kahn's algorithm. Returns the topics that never reach in-degree 0: those on a cycle or
     * downstream of one. Empty means the graph is acyclic.
     */
    private fun topicsOnOrAfterCycle(): List<String> {
        val inDegree = topics.associate { it.id to prerequisites.getValue(it.id).size }.toMutableMap()
        val ready = ArrayDeque(topics.map { it.id }.filter { inDegree.getValue(it) == 0 })
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            inDegree.remove(id)
            for (dependent in dependents.getValue(id)) {
                val left = inDegree.getValue(dependent) - 1
                inDegree[dependent] = left
                if (left == 0) ready.addLast(dependent)
            }
        }
        return inDegree.keys.toList()
    }
}
