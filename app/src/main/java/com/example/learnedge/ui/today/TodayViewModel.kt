package com.example.learnedge.ui.today

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.learnedge.appContainer
import com.example.learnedge.ui.plan.StudyItemUi
import com.example.learnedge.ui.plan.toUi
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodayUiState(
    val loading: Boolean = true,
    val date: LocalDate = LocalDate.now(),
    val trackName: String = "",
    val items: List<StudyItemUi> = emptyList(),
    /** Next day with study items, shown when nothing is planned today. */
    val nextStudyDay: LocalDate? = null,
) {
    val totalMinutes: Int get() = items.sumOf { it.minutes }
    val doneMinutes: Int get() = items.filter { it.done }.sumOf { it.minutes }
}

class TodayViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer

    val state: StateFlow<TodayUiState> = combine(
        container.plans.observeItems(),
        container.learnerState.observe(),
        container.profiles.observe(),
    ) { items, mastery, profile ->
        if (profile == null) return@combine TodayUiState(loading = false)
        val track = container.curriculum.track(profile.trackId)
        val today = LocalDate.now()
        val todayDay = today.toEpochDay()
        TodayUiState(
            loading = false,
            date = today,
            trackName = track.name,
            items = items.filter { it.dayEpochDay == todayDay }.map { it.toUi(track, mastery) },
            nextStudyDay = items.mapNotNull { it.dayEpochDay }.filter { it > todayDay }.minOrNull()?.let(LocalDate::ofEpochDay),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    init {
        // A plan made on an earlier day is rebuilt so missed topics move forward.
        viewModelScope.launch { container.plans.ensureFresh() }
    }

    fun setDone(itemId: Long, done: Boolean) {
        viewModelScope.launch { container.plans.setDone(itemId, done) }
    }
}
