package com.example.learnedge.domain

/** How a topic looks on the knowledge map. [symbol] is drawn next to the colour so colour is never the only cue. */
enum class NodeState(val label: String, val symbol: String) {
    LOCKED("Locked", "🔒"),
    WEAK("Weak", "⚠"),
    LEARNING("Learning", "◐"),
    STRONG("Strong", "★"),
    MASTERED("Mastered", "✓"),
}

/** Where a topic is drawn: [layer] is the column from the left, [row] its place within that column. */
data class MapPosition(val layer: Int, val row: Int)

object NodeStates {
    /**
     * Mastered (P(L) >= 0.95) wins. Otherwise a topic is locked while any prerequisite is still at
     * beginner level (P(L) < 0.3); after that it is weak (< 0.3), learning (< 0.7) or strong. The
     * thresholds are the explanation level bands, so the map and the lessons agree.
     */
    fun of(topicId: String, graph: KnowledgeGraph, mastery: Map<String, Double>): NodeState {
        val pL = mastery[topicId] ?: 0.0
        if (Bkt.isMastered(pL)) return NodeState.MASTERED
        if (graph.getPrerequisites(topicId).any { (mastery[it] ?: 0.0) < LevelBand.BEGINNER_BELOW }) return NodeState.LOCKED
        return when {
            pL < LevelBand.BEGINNER_BELOW -> NodeState.WEAK
            pL < LevelBand.INTERMEDIATE_BELOW -> NodeState.LEARNING
            else -> NodeState.STRONG
        }
    }
}

/** Lays the topic graph out in columns, left to right by prerequisite depth. Pure and deterministic. */
object GraphLayout {
    private const val SWEEPS = 2

    /**
     * A topic's layer is the length of its longest prerequisite chain, so every topic is strictly to
     * the right of all of its prerequisites. Rows within a layer are ordered by the average row of
     * the topic's neighbours (a few forward and backward sweeps) to reduce crossing edges; ties keep
     * curriculum order.
     */
    fun layout(graph: KnowledgeGraph): Map<String, MapPosition> {
        val order = graph.topics.map { it.id }
        val curriculumIndex = order.withIndex().associate { (i, id) -> id to i }

        val layerOf = HashMap<String, Int>()
        fun layer(id: String): Int = layerOf.getOrPut(id) { graph.getPrerequisites(id).maxOfOrNull { layer(it) + 1 } ?: 0 }
        val layers: Map<Int, List<String>> = order.groupBy { layer(it) }.toSortedMap()

        val row = HashMap<String, Int>()
        layers.values.forEach { ids -> ids.forEachIndexed { r, id -> row[id] = r } }

        fun reorder(ids: List<String>, neighbours: (String) -> Set<String>) {
            ids.sortedWith(
                compareBy<String>(
                    { id -> neighbours(id).takeIf { it.isNotEmpty() }?.map { row.getValue(it) }?.average() ?: row.getValue(id).toDouble() },
                    { curriculumIndex.getValue(it) },
                ),
            ).forEachIndexed { r, id -> row[id] = r }
        }

        repeat(SWEEPS) {
            layers.entries.filter { it.key > 0 }.forEach { (_, ids) -> reorder(ids) { graph.getPrerequisites(it) } }
            layers.entries.reversed().forEach { (_, ids) -> reorder(ids) { graph.getDependents(it) } }
        }
        return order.associateWith { MapPosition(layerOf.getValue(it), row.getValue(it)) }
    }
}
