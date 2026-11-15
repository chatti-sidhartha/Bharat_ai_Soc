package com.example.learnedge.ui.plan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.learnedge.appContainer
import com.example.learnedge.data.plan.PlanRepository
import com.example.learnedge.domain.PlanReasons
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlanDayUi(val date: LocalDate, val items: List<StudyItemUi>) {
    val minutes: Int get() = items.sumOf { it.minutes }
}

data class PlanUiState(
    val loading: Boolean = true,
    val trackName: String = "",
    val deadline: LocalDate? = null,
    val dailyMinutes: Int = 0,
    val days: List<PlanDayUi> = emptyList(),
    val deferred: List<StudyItemUi> = emptyList(),
    val feasible: Boolean = true,
    val regenerating: Boolean = false,
) {
    val remainingMinutes: Int get() = days.sumOf { d -> d.items.filterNot { it.done }.sumOf { it.minutes } }
    val topicCount: Int get() = days.flatMap { it.items }.map { it.topicName }.distinct().size
}

class PlanViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer
    private val regenerating = MutableStateFlow(false)

    val state: StateFlow<PlanUiState> = combine(
        container.plans.observeItems(),
        container.plans.observePlan(),
        container.learnerState.observe(),
        container.profiles.observe(),
        regenerating,
    ) { items, plan, mastery, profile, busy ->
        if (profile == null) return@combine PlanUiState(loading = false)
        val track = container.curriculum.track(profile.trackId)
        val todayDay = LocalDate.now().toEpochDay()
        // Reasons only read the planner's numbers; they never influence the plan.
        val reasons = PlanReasons.compute(track, mastery.mapValues { it.value.mastery }, profile.selfRatings)
        PlanUiState(
            loading = false,
            trackName = track.name,
            deadline = profile.deadline,
            dailyMinutes = profile.dailyMinutes,
            days = items.filter { (it.dayEpochDay ?: Long.MIN_VALUE) >= todayDay }
                .groupBy { it.dayEpochDay!! }
                .map { (day, dayItems) -> PlanDayUi(LocalDate.ofEpochDay(day), dayItems.map { it.toUi(track, mastery, reasons) }) },
            deferred = items.filter { it.dayEpochDay == null }.map { it.toUi(track, mastery, reasons) },
            feasible = plan?.feasible ?: true,
            regenerating = busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanUiState())

    fun regenerate() {
        if (regenerating.value) return
        regenerating.value = true
        viewModelScope.launch {
            try {
                container.plans.regenerate(PlanRepository.Mode.FROM_TODAY)
            } finally {
                regenerating.value = false
            }
        }
    }
}
