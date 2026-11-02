package com.example.learnedge.data.plan

import android.util.Log
import com.example.learnedge.data.curriculum.CurriculumRepository
import com.example.learnedge.data.db.AppDatabase
import com.example.learnedge.data.db.PlanItemEntity
import com.example.learnedge.data.db.StudyPlanEntity
import com.example.learnedge.data.learner.LearnerStateRepository
import com.example.learnedge.data.profile.ProfileRepository
import com.example.learnedge.domain.Bkt
import com.example.learnedge.domain.StudyPlanner
import com.example.learnedge.domain.TopicProgress
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stores the study plan and rebuilds it whenever its inputs change. Done items are history and are
 * never deleted; everything not yet done is replanned by the deterministic [StudyPlanner].
 */
class PlanRepository(
    db: AppDatabase,
    private val curriculum: CurriculumRepository,
    private val profiles: ProfileRepository,
    private val learnerState: LearnerStateRepository,
) {
    private val dao = db.planDao()
    private val lock = Mutex()

    enum class Mode {
        /** Replan today and later (profile change, new diagnostic, new day, "Regenerate plan"). */
        FROM_TODAY,

        /** Keep today's items as they are and replan from tomorrow (after marking a topic done). */
        FROM_TOMORROW,
    }

    fun observePlan(): Flow<StudyPlanEntity?> = dao.observePlan()

    fun observeItems(): Flow<List<PlanItemEntity>> = dao.observeItems()

    /** Regenerates if there is no plan yet or it was made on an earlier day. */
    suspend fun ensureFresh(today: LocalDate = LocalDate.now()) {
        val plan = dao.getPlan()
        if (plan == null || plan.generatedOnEpochDay != today.toEpochDay()) regenerate(Mode.FROM_TODAY, today)
    }

    suspend fun regenerate(mode: Mode, today: LocalDate = LocalDate.now()) = lock.withLock {
        val profile = profiles.get() ?: return@withLock
        if (!profile.diagnosticCompleted) return@withLock
        val track = curriculum.track(profile.trackId)
        val todayDay = today.toEpochDay()

        val existing = dao.getItems()
        val switchedTrack = dao.getPlan()?.let { it.trackId != profile.trackId } ?: false
        val keep = if (switchedTrack) {
            emptyList()
        } else {
            existing.filter { it.done || (mode == Mode.FROM_TOMORROW && it.dayEpochDay == todayDay) }
        }

        // Minutes already done (or kept for today) per topic decide what is left to plan.
        val coveredMinutes = keep.filter { it.topicId in track.graph }
            .groupBy { it.topicId }.mapValues { (_, items) -> items.sumOf { it.minutes } }
        val satisfied = coveredMinutes.filter { (id, minutes) -> minutes >= track.graph.topic(id).estimatedMinutes }.keys
        val remaining = coveredMinutes
            .filterKeys { it !in satisfied }
            .mapValues { (id, minutes) -> track.graph.topic(id).estimatedMinutes - minutes }

        val start = if (mode == Mode.FROM_TOMORROW) today.plusDays(1) else today
        val usedOnStart = keep.filter { it.dayEpochDay == start.toEpochDay() }.sumOf { it.minutes }
        val mastery = learnerState.get().mapValues { it.value.mastery }
        val plan = StudyPlanner.generate(track, profile, mastery, start, satisfied, remaining, usedOnStart)

        val nextPosition = keep.groupBy { it.dayEpochDay }.mapValues { (_, items) -> items.maxOf { it.position } + 1 }
        val newItems = plan.days.flatMap { day ->
            val epochDay = day.date.toEpochDay()
            val offset = nextPosition[epochDay] ?: 0
            day.items.mapIndexed { i, item ->
                PlanItemEntity(
                    topicId = item.topicId,
                    dayEpochDay = epochDay,
                    position = offset + i,
                    minutes = item.minutes,
                    part = item.part,
                    parts = item.parts,
                    priority = item.priority,
                )
            }
        } + plan.deferred.mapIndexed { i, d ->
            PlanItemEntity(
                topicId = d.topicId,
                dayEpochDay = null,
                position = i,
                minutes = track.graph.topic(d.topicId).estimatedMinutes,
                part = 1,
                parts = 1,
                priority = d.priority,
                deferredReason = d.reason,
            )
        }

        val keepIds = keep.mapTo(HashSet()) { it.id }
        dao.replace(
            StudyPlanEntity(
                trackId = profile.trackId,
                generatedOnEpochDay = todayDay,
                createdAt = System.currentTimeMillis(),
                deadlineEpochDay = profile.deadline.toEpochDay(),
                dailyMinutes = profile.dailyMinutes,
                totalMinutes = plan.totalMinutes,
                feasible = plan.feasible,
            ),
            deleteIds = existing.map { it.id }.filter { it !in keepIds },
            newItems = newItems,
        )
        Log.i(TAG, "Plan ($mode): ${plan.days.size} days, ${plan.totalMinutes} min, ${plan.deferred.size} deferred")
    }

    /**
     * Marks an item studied (one BKT learning transition on its topic) or un-marks it (restores the
     * previous mastery), then replans from tomorrow.
     */
    suspend fun setDone(itemId: Long, done: Boolean) {
        val item = dao.getItem(itemId) ?: return
        if (item.done == done || item.dayEpochDay == null) return
        val current = learnerState.get()[item.topicId] ?: TopicProgress(item.topicId, 0.0)
        if (done) {
            learnerState.save(listOf(current.copy(mastery = Bkt.learn(current.mastery))))
            dao.updateItem(item.copy(done = true, doneAt = System.currentTimeMillis(), pLBeforeDone = current.mastery))
        } else {
            learnerState.save(listOf(current.copy(mastery = item.pLBeforeDone ?: current.mastery)))
            dao.updateItem(item.copy(done = false, doneAt = null, pLBeforeDone = null))
        }
        regenerate(Mode.FROM_TOMORROW)
    }

    private companion object {
        const val TAG = "LearnEdgePlan"
    }
}
