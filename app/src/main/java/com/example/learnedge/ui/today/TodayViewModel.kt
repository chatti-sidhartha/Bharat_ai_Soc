package com.example.learnedge.ui.today

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.learnedge.appContainer
import com.example.learnedge.data.nightshift.NightShiftProgress
import com.example.learnedge.data.nightshift.PreparedSummary
import com.example.learnedge.data.tips.TipsProgress
import com.example.learnedge.domain.PlanReasons
import com.example.learnedge.domain.SkillGaps
import com.example.learnedge.llm.tips.DailyTips
import com.example.learnedge.llm.tips.GapLine
import com.example.learnedge.llm.tips.TipInput
import com.example.learnedge.llm.tips.TipTopic
import com.example.learnedge.ui.plan.StudyItemUi
import com.example.learnedge.ui.plan.toUi
import java.time.LocalDate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TodayUiState(
    val loading: Boolean = true,
    val date: LocalDate = LocalDate.now(),
    val trackName: String = "",
    val items: List<StudyItemUi> = emptyList(),
    /** Next day with study items, shown when nothing is planned today. */
    val nextStudyDay: LocalDate? = null,
    /** Summary and tips written for exactly today's topics, or null while they are (re)generated. */
    val tips: DailyTips? = null,
    val tipsProgress: TipsProgress = TipsProgress(),
    /** What the tip generator is asked about; drives regeneration when today's topics change. */
    val tipInput: TipInput? = null,
) {
    val totalMinutes: Int get() = items.sumOf { it.minutes }
    val doneMinutes: Int get() = items.filter { it.done }.sumOf { it.minutes }
}

data class NightUi(
    val summary: PreparedSummary = PreparedSummary(),
    val progress: NightShiftProgress = NightShiftProgress(),
    val enabled: Boolean = true,
)

class TodayViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer
    private val today = LocalDate.now()

    val state: StateFlow<TodayUiState> = combine(
        container.plans.observeItems(),
        container.learnerState.observe(),
        container.profiles.observe(),
        container.tips.observe(today),
        container.tips.progress,
    ) { items, mastery, profile, stored, progress ->
        if (profile == null) return@combine TodayUiState(loading = false)
        val track = container.curriculum.track(profile.trackId)
        val todayDay = today.toEpochDay()
        val todayItems = items.filter { it.dayEpochDay == todayDay }
        // Reasons only read the planner's numbers; they never influence the plan.
        val reasons = PlanReasons.compute(track, mastery.mapValues { it.value.mastery }, profile.selfRatings)

        val gaps = SkillGaps.compute(track, mastery.mapValues { it.value.mastery }, profile.selfRatings).values
            .filter { it.gap > 0 }
            .sortedByDescending { it.gap }
            .take(MAX_GAPS_IN_PROMPT)
            .map { GapLine(it.skill.name, it.currentLevel, it.skill.targetLevel) }
        val tipInput = todayItems.takeIf { it.isNotEmpty() }?.let {
            TipInput(
                date = today,
                trackName = track.name,
                interests = profile.interests,
                dailyMinutes = profile.dailyMinutes,
                deadline = profile.deadline,
                gaps = gaps,
                // A topic split over several days appears once, with today's minutes.
                topics = it.groupBy { item -> item.topicId }.map { (topicId, parts) ->
                    val topic = track.graph.topic(topicId)
                    TipTopic(topicId, topic.name, topic.description, parts.sumOf { p -> p.minutes })
                },
            )
        }

        TodayUiState(
            loading = false,
            date = today,
            trackName = track.name,
            items = todayItems.map { it.toUi(track, mastery, reasons) },
            nextStudyDay = items.mapNotNull { it.dayEpochDay }.filter { it > todayDay }.minOrNull()?.let(LocalDate::ofEpochDay),
            tips = stored?.takeIf { it.signature == tipInput?.signature }?.tips,
            tipsProgress = progress,
            tipInput = tipInput,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    init {
        // A plan made on an earlier day is rebuilt so missed topics move forward.
        viewModelScope.launch { container.plans.ensureFresh() }
        // Tips are written in the background whenever today's topics change; the UI never waits.
        viewModelScope.launch {
            state.mapNotNull { it.tipInput }
                .distinctUntilChangedBy { it.signature }
                .collect { container.tips.ensure(it) }
        }
    }

    /** The "Prepared for you" card: how much of the next two plan days is ready, the live progress of a run, and the switch. */
    val night: StateFlow<NightUi> = combine(
        container.nightShift.summary(),
        container.nightShift.progress,
        container.nightShift.prefs.enabled,
    ) { summary, progress, enabled -> NightUi(summary, progress, enabled) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NightUi())

    /** The same pipeline as Night Shift, run now in the foreground. It runs in the app scope, so leaving the screen does not stop it. */
    fun prepareNow() {
        container.appScope.launch { container.nightShift.run(foreground = true) }
    }

    fun setNightShift(enabled: Boolean) = container.nightShift.setEnabled(enabled)

    fun setDone(itemId: Long, done: Boolean) {
        viewModelScope.launch { container.plans.setDone(itemId, done) }
    }

    /** Asks the model again, e.g. after rule-based text was used because the model failed. */
    fun regenerateTips() {
        state.value.tipInput?.let { container.tips.ensure(it, force = true) }
    }

    private companion object {
        const val MAX_GAPS_IN_PROMPT = 3
    }
}
