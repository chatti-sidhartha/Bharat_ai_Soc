package com.example.learnedge.domain

import java.time.LocalDate
import kotlin.math.min

/** Study [minutes] of a topic on one day. Topics longer than a day are split into [part] of [parts]. */
data class PlanItem(
    val topicId: String,
    val minutes: Int,
    val part: Int = 1,
    val parts: Int = 1,
)

data class DayPlan(val date: LocalDate, val items: List<PlanItem>) {
    val minutes: Int get() = items.sumOf { it.minutes }
}

data class DeferredTopic(val topicId: String, val priority: Double, val reason: String)

data class StudyPlan(
    val days: List<DayPlan>,
    val deferred: List<DeferredTopic>,
    val totalMinutes: Int,
    val feasible: Boolean,
)

/** Packs a learning path into days within the daily time budget and the deadline. */
object Scheduler {
    /**
     * Greedy packing in path order: a topic that doesn't fit in the day's remaining time moves to the
     * next day; a topic longer than [dailyMinutes] starts on a fresh day and is split across days.
     * If the packed plan needs more than [days] days, the lowest-priority topic that no remaining
     * topic depends on is deferred, repeatedly, so the kept plan never misses a prerequisite.
     */
    fun schedule(
        path: List<PlannedTopic>,
        graph: KnowledgeGraph,
        startDate: LocalDate,
        days: Int,
        dailyMinutes: Int,
    ): StudyPlan {
        require(dailyMinutes > 0) { "dailyMinutes must be positive, was $dailyMinutes" }
        val kept = path.toMutableList()
        val deferred = ArrayList<DeferredTopic>()
        var packed = pack(kept, dailyMinutes)

        while (packed.size > days && kept.isNotEmpty()) {
            val keptIds = kept.mapTo(HashSet()) { it.topic.id }
            // Only "leaves" can go: deferring a topic another kept topic needs would break the order.
            val victim = kept.withIndex()
                .filter { (_, t) -> graph.getDependents(t.topic.id).none { it in keptIds } }
                .minWith(compareBy<IndexedValue<PlannedTopic>> { it.value.priority }.thenByDescending { it.index })
                .value
            kept -= victim
            deferred += DeferredTopic(
                victim.topic.id,
                victim.priority,
                "Doesn't fit before the deadline (${days * dailyMinutes} min available in $days days); " +
                    "lowest priority among topics nothing else depends on.",
            )
            packed = pack(kept, dailyMinutes)
        }

        return StudyPlan(
            days = packed.mapIndexed { i, items -> DayPlan(startDate.plusDays(i.toLong()), items) },
            deferred = deferred,
            totalMinutes = kept.sumOf { it.topic.estimatedMinutes },
            feasible = deferred.isEmpty(),
        )
    }

    private fun pack(path: List<PlannedTopic>, dailyMinutes: Int): List<List<PlanItem>> {
        val days = ArrayList<MutableList<PlanItem>>()
        var today = mutableListOf<PlanItem>()
        var remaining = dailyMinutes

        fun nextDay() {
            if (today.isNotEmpty()) days += today
            today = mutableListOf()
            remaining = dailyMinutes
        }

        for ((topic, _) in path) {
            val minutes = topic.estimatedMinutes
            if (minutes <= dailyMinutes) {
                if (minutes > remaining) nextDay()
                today += PlanItem(topic.id, minutes)
                remaining -= minutes
            } else {
                if (remaining < dailyMinutes) nextDay()
                val parts = (minutes + dailyMinutes - 1) / dailyMinutes
                var left = minutes
                for (part in 1..parts) {
                    val chunk = min(dailyMinutes, left)
                    today += PlanItem(topic.id, chunk, part, parts)
                    remaining -= chunk
                    left -= chunk
                    if (left > 0) nextDay()
                }
            }
        }
        if (today.isNotEmpty()) days += today
        return days
    }
}
