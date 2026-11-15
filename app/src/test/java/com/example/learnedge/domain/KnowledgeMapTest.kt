package com.example.learnedge.domain

import com.example.learnedge.data.curriculum.CurriculumParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeMapTest {
    private val projectDir = System.getProperty("projectDir") ?: error("projectDir system property not set by Gradle")
    private val tracks by lazy { CurriculumParser.parse(File(projectDir, "src/main/assets/curriculum.json").readText()) }

    // --- Layout ---

    @Test
    fun noTopicIsPlacedLeftOfAnyOfItsPrerequisites_inBothTracks() {
        tracks.forEach { track ->
            val positions = GraphLayout.layout(track.graph)
            track.graph.topics.forEach { topic ->
                track.graph.getPrerequisites(topic.id).forEach { prerequisite ->
                    assertTrue(
                        "${topic.id} (layer ${positions.getValue(topic.id).layer}) must be right of $prerequisite (layer ${positions.getValue(prerequisite).layer})",
                        positions.getValue(topic.id).layer > positions.getValue(prerequisite).layer,
                    )
                }
            }
        }
    }

    @Test
    fun everyTopicIsPlacedOnce_rowsAreContiguousPerLayer_andRootsAreInTheFirstLayer() {
        tracks.forEach { track ->
            val positions = GraphLayout.layout(track.graph)
            assertEquals(track.graph.topics.map { it.id }.toSet(), positions.keys)
            positions.values.groupBy { it.layer }.forEach { (layer, nodes) ->
                assertEquals("rows in layer $layer of ${track.id}", (0 until nodes.size).toList(), nodes.map { it.row }.sorted())
            }
            track.graph.topics.filter { track.graph.getPrerequisites(it.id).isEmpty() }
                .forEach { assertEquals("${it.id} has no prerequisites", 0, positions.getValue(it.id).layer) }
        }
    }

    @Test
    fun aTopicsLayerIsItsLongestPrerequisiteChain() {
        val java = tracks.first { it.id == "java-backend" }.graph
        val positions = GraphLayout.layout(java)
        // j-basics -> j-control -> j-methods -> j-oop -> j-inherit -> j-interfaces -> j-generics -> j-collections -> j-lambdas -> j-concurrency
        assertEquals(0, positions.getValue("j-basics").layer)
        assertEquals(9, positions.getValue("j-concurrency").layer)
    }

    @Test
    fun layoutIsDeterministic() {
        tracks.forEach { assertEquals(GraphLayout.layout(it.graph), GraphLayout.layout(it.graph)) }
    }

    // --- Node states ---

    private val graph = KnowledgeGraph(
        listOf(Topic("a", "A", "s", 30), Topic("b", "B", "s", 30)),
        mapOf("b" to setOf("a")),
    )

    @Test
    fun aTopicIsLockedWhileAPrerequisiteIsStillAtBeginnerLevel_andUnlocksOnceItIsNot() {
        assertEquals(NodeState.LOCKED, NodeStates.of("b", graph, mapOf("a" to 0.26, "b" to 0.26)))
        assertEquals(NodeState.WEAK, NodeStates.of("b", graph, mapOf("a" to 0.98, "b" to 0.26)))
    }

    @Test
    fun statesFollowTheMasteryBands() {
        fun state(pL: Double) = NodeStates.of("a", graph, mapOf("a" to pL))
        assertEquals(NodeState.WEAK, state(0.0))
        assertEquals(NodeState.WEAK, state(0.299))
        assertEquals(NodeState.LEARNING, state(0.3))
        assertEquals(NodeState.LEARNING, state(0.699))
        assertEquals(NodeState.STRONG, state(0.7))
        assertEquals(NodeState.STRONG, state(0.949))
        assertEquals(NodeState.MASTERED, state(0.95))
    }

    @Test
    fun aMasteredTopicIsMasteredEvenIfAPrerequisiteIsWeak() {
        assertEquals(NodeState.MASTERED, NodeStates.of("b", graph, mapOf("a" to 0.1, "b" to 0.99)))
    }

    @Test
    fun colourIsNeverTheOnlyCue_everyStateHasItsOwnLabelAndSymbol() {
        val states = NodeState.entries
        assertEquals(states.size, states.map { it.label }.toSet().size)
        assertEquals(states.size, states.map { it.symbol }.toSet().size)
        assertTrue(states.all { it.label.isNotBlank() && it.symbol.isNotBlank() })
    }
}
