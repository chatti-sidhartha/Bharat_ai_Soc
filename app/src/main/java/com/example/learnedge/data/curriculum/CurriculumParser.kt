package com.example.learnedge.data.curriculum

import com.example.learnedge.domain.KnowledgeGraph
import com.example.learnedge.domain.Skill
import com.example.learnedge.domain.Topic
import com.example.learnedge.domain.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Reads assets/curriculum.json into validated domain [Track]s. No Android dependencies, so it is unit-tested on the JVM. */
object CurriculumParser {
    private val json = Json { ignoreUnknownKeys = false }

    /** Throws [IllegalArgumentException] (or a serialization exception) if the file is malformed or has a cycle. */
    fun parse(text: String): List<Track> {
        val file = json.decodeFromString<CurriculumFile>(text)
        val tracks = file.tracks.map { it.toDomain() }
        val duplicateTopics = tracks.flatMap { t -> t.graph.topics.map { it.id } }.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicateTopics.isEmpty()) { "Topic ids must be unique across tracks: $duplicateTopics" }
        val duplicateSkills = tracks.flatMap { t -> t.skills.map { it.id } }.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicateSkills.isEmpty()) { "Skill ids must be unique across tracks: $duplicateSkills" }
        return tracks
    }

    private fun TrackJson.toDomain(): Track = try {
        Track(
            id = id,
            name = name,
            skills = skills.map { Skill(it.id, it.name, it.targetLevel, it.weight) },
            graph = KnowledgeGraph(
                topics = topics.map { Topic(it.id, it.name, it.skillId, it.estimatedMinutes, it.description, it.keyPoints) },
                prerequisites = topics.associate { it.id to it.prerequisites.toSet() },
            ),
        )
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid track '$id' in curriculum.json: ${e.message}", e)
    }
}

@Serializable
private data class CurriculumFile(val tracks: List<TrackJson>)

@Serializable
private data class TrackJson(val id: String, val name: String, val skills: List<SkillJson>, val topics: List<TopicJson>)

@Serializable
private data class SkillJson(val id: String, val name: String, val targetLevel: Int, val weight: Double)

@Serializable
private data class TopicJson(
    val id: String,
    val name: String,
    val skillId: String,
    val estimatedMinutes: Int,
    val prerequisites: List<String> = emptyList(),
    val description: String = "",
    val keyPoints: List<String> = emptyList(),
)
