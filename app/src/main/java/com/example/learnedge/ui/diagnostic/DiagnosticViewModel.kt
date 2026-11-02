package com.example.learnedge.ui.diagnostic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.learnedge.appContainer
import com.example.learnedge.data.plan.PlanRepository
import com.example.learnedge.domain.DiagnosticAnswer
import com.example.learnedge.domain.DiagnosticQuestion
import com.example.learnedge.domain.InitialMastery
import com.example.learnedge.domain.SkillGaps
import com.example.learnedge.domain.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Result for one skill after the diagnostic. Levels are on the 0–10 scale. */
data class SkillResult(
    val skillName: String,
    val selfRating: Int,
    val estimatedLevel: Double,
    val targetLevel: Int,
    val correct: Int,
    val asked: Int,
)

data class DiagnosticUiState(
    val loading: Boolean = true,
    val track: Track? = null,
    val questions: List<DiagnosticQuestion> = emptyList(),
    val index: Int = 0,
    val selected: Int? = null,
    val answers: List<DiagnosticAnswer> = emptyList(),
    val saving: Boolean = false,
    /** Non-null once the quiz is finished and mastery is saved. */
    val results: List<SkillResult>? = null,
) {
    val current: DiagnosticQuestion? get() = questions.getOrNull(index)
    val isLast: Boolean get() = index == questions.lastIndex
}

class DiagnosticViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer
    private val _state = MutableStateFlow(DiagnosticUiState())
    val state: StateFlow<DiagnosticUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = checkNotNull(container.profiles.get()) { "Diagnostic opened without a profile" }
            val track = container.curriculum.track(profile.trackId)
            _state.update { it.copy(loading = false, track = track, questions = container.diagnostics.questionsFor(track)) }
        }
    }

    fun select(option: Int) {
        if (_state.value.results == null) _state.update { it.copy(selected = option) }
    }

    fun next() {
        val s = _state.value
        val question = s.current ?: return
        val choice = s.selected ?: return
        val answers = s.answers + DiagnosticAnswer(question.id, question.skillId, question.topicId, question.isCorrect(choice))
        if (s.isLast) {
            _state.update { it.copy(answers = answers, selected = null, saving = true) }
            finish(answers)
        } else {
            _state.update { it.copy(answers = answers, index = it.index + 1, selected = null) }
        }
    }

    private fun finish(answers: List<DiagnosticAnswer>) = viewModelScope.launch {
        val track = checkNotNull(_state.value.track)
        val profile = checkNotNull(container.profiles.get())
        val mastery = InitialMastery.compute(track, profile.selfRatings, answers)
        container.learnerState.save(mastery.values)
        container.profiles.setDiagnosticCompleted(true)
        container.plans.regenerate(PlanRepository.Mode.FROM_TODAY)

        val pL = mastery.mapValues { it.value.mastery }
        val results = track.skills.map { skill ->
            val onSkill = answers.filter { it.skillId == skill.id }
            SkillResult(
                skillName = skill.name,
                selfRating = profile.selfRatings[skill.id] ?: 0,
                estimatedLevel = SkillGaps.currentLevel(track, skill.id, pL, profile.selfRatings),
                targetLevel = skill.targetLevel,
                correct = onSkill.count { it.correct },
                asked = onSkill.size,
            )
        }
        _state.update { it.copy(saving = false, results = results) }
    }
}
