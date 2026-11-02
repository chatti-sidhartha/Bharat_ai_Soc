package com.example.learnedge.data.curriculum

import android.content.res.AssetManager
import android.util.Log
import androidx.room.withTransaction
import com.example.learnedge.data.db.AppDatabase
import com.example.learnedge.data.db.PrerequisiteEntity
import com.example.learnedge.data.db.SkillEntity
import com.example.learnedge.data.db.TopicEntity
import com.example.learnedge.data.db.TrackEntity
import com.example.learnedge.domain.KnowledgeGraph
import com.example.learnedge.domain.Skill
import com.example.learnedge.domain.Topic
import com.example.learnedge.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The curriculum lives in Room, seeded once from assets/curriculum.json. */
class CurriculumRepository(private val db: AppDatabase, private val assets: AssetManager) {
    private val dao = db.curriculumDao()
    private val seedLock = Mutex()
    @Volatile private var seeded = false
    private val cache = HashMap<String, Track>()

    /** Inserts the curriculum on first launch. Every read calls this, so callers never see an empty database. */
    suspend fun seedIfEmpty() = seedLock.withLock {
        if (seeded) return@withLock
        withContext(Dispatchers.IO) {
            if (dao.trackCount() == 0) {
                val tracks = CurriculumParser.parse(assets.open(ASSET).bufferedReader().use { it.readText() })
                db.withTransaction { insert(tracks) }
                Log.i(TAG, "Seeded ${tracks.size} tracks, ${tracks.sumOf { it.graph.topics.size }} topics")
            }
        }
        seeded = true
    }

    suspend fun tracks(): List<Track> {
        seedIfEmpty()
        return dao.tracks().map { track(it.id) }
    }

    suspend fun track(trackId: String): Track {
        seedIfEmpty()
        synchronized(cache) { cache[trackId] }?.let { return it }
        val entity = dao.tracks().firstOrNull { it.id == trackId } ?: throw NoSuchElementException("Unknown track $trackId")
        val edges = dao.prerequisites(trackId).groupBy({ it.topicId }, { it.prerequisiteId })
        val track = Track(
            id = entity.id,
            name = entity.name,
            skills = dao.skills(trackId).map { Skill(it.id, it.name, it.targetLevel, it.weight) },
            graph = KnowledgeGraph(
                topics = dao.topics(trackId).map { Topic(it.id, it.name, it.skillId, it.estimatedMinutes, it.description) },
                prerequisites = edges.mapValues { it.value.toSet() },
            ),
        )
        synchronized(cache) { cache[trackId] = track }
        return track
    }

    private suspend fun insert(tracks: List<Track>) {
        dao.insertTracks(tracks.mapIndexed { i, t -> TrackEntity(t.id, t.name, i) })
        dao.insertSkills(tracks.flatMap { t ->
            t.skills.mapIndexed { i, s -> SkillEntity(s.id, t.id, s.name, s.targetLevel, s.weight, i) }
        })
        dao.insertTopics(tracks.flatMap { t ->
            t.graph.topics.mapIndexed { i, topic ->
                TopicEntity(topic.id, t.id, topic.skillId, topic.name, topic.estimatedMinutes, topic.description, i)
            }
        })
        dao.insertPrerequisites(tracks.flatMap { t ->
            t.graph.topics.flatMap { topic -> t.graph.getPrerequisites(topic.id).map { PrerequisiteEntity(topic.id, it) } }
        })
    }

    private companion object {
        const val ASSET = "curriculum.json"
        const val TAG = "LearnEdgeData"
    }
}
