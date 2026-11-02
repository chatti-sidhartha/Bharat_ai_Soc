package com.example.learnedge.data.curriculum

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CurriculumParserTest {
    private val assetText: String by lazy {
        val projectDir = System.getProperty("projectDir") ?: error("projectDir system property not set by Gradle")
        File(projectDir, "src/main/assets/curriculum.json").readText()
    }

    // Parsing builds a KnowledgeGraph per track, which already rejects cycles and unknown ids.
    private val tracks by lazy { CurriculumParser.parse(assetText) }

    @Test
    fun loadsBothTracks() {
        assertEquals(listOf("java-backend", "dsa"), tracks.map { it.id })
        assertEquals(listOf("Java Backend Developer", "Data Structures & Algorithms"), tracks.map { it.name })
    }

    @Test
    fun eachTrackHas25To35Topics() {
        tracks.forEach { track ->
            val count = track.graph.topics.size
            assertTrue("${track.id} has $count topics", count in 25..35)
        }
    }

    @Test
    fun everyPrerequisiteIdExistsInTheSameTrack() {
        tracks.forEach { track ->
            track.graph.topics.forEach { topic ->
                track.graph.getPrerequisites(topic.id).forEach { prereq ->
                    assertTrue("${topic.id} -> $prereq", prereq in track.graph)
                }
            }
        }
    }

    @Test
    fun graphsAreAcyclic_everyTopicCanBeOrdered() {
        tracks.forEach { track ->
            // Repeatedly take topics whose prerequisites are all taken; an acyclic graph empties completely.
            val done = HashSet<String>()
            var progress = true
            while (progress) {
                val next = track.graph.topics.filter { it.id !in done && track.graph.getPrerequisites(it.id).all(done::contains) }
                progress = next.isNotEmpty()
                next.forEach { done += it.id }
            }
            assertEquals(track.graph.topics.size, done.size)
        }
    }

    @Test
    fun javaTrack_followsTheDesignedChain() {
        val g = tracks.first { it.id == "java-backend" }.graph
        // Java basics -> OOP -> Collections -> JDBC -> Spring Boot (data access), and SQL -> JDBC.
        assertTrue("j-oop" in g.getDescendants("j-basics"))
        assertTrue("j-collections" in g.getDescendants("j-oop"))
        assertTrue("s-jdbc" in g.getDescendants("j-collections"))
        assertTrue("sp-data" in g.getDescendants("s-jdbc"))
        assertTrue("s-jdbc" in g.getDescendants("s-intro"))
    }

    @Test
    fun everySkillHasTopicsAndAValidTarget() {
        tracks.forEach { track ->
            track.skills.forEach { skill ->
                assertTrue("${skill.id} has no topics", track.topicsOf(skill.id).isNotEmpty())
                assertTrue(skill.targetLevel in 0..10)
            }
        }
    }

    @Test
    fun cycleInJson_failsLoudly() {
        val cyclic = """
            {"tracks":[{"id":"t","name":"T","skills":[{"id":"s","name":"S","targetLevel":5,"weight":1.0}],
            "topics":[
              {"id":"a","name":"A","skillId":"s","estimatedMinutes":10,"prerequisites":["b"]},
              {"id":"b","name":"B","skillId":"s","estimatedMinutes":10,"prerequisites":["a"]}
            ]}]}
        """.trimIndent()
        val e = assertThrows(IllegalArgumentException::class.java) { CurriculumParser.parse(cyclic) }
        assertTrue(e.message!!, e.message!!.contains("cycle"))
    }

    @Test
    fun unknownPrerequisiteInJson_failsLoudly() {
        val broken = """
            {"tracks":[{"id":"t","name":"T","skills":[{"id":"s","name":"S","targetLevel":5,"weight":1.0}],
            "topics":[{"id":"a","name":"A","skillId":"s","estimatedMinutes":10,"prerequisites":["nope"]}]}]}
        """.trimIndent()
        assertThrows(IllegalArgumentException::class.java) { CurriculumParser.parse(broken) }
    }
}
