package com.example.learnedge.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.learnedge.appContainer
import com.example.learnedge.data.plan.PlanRepository
import com.example.learnedge.domain.LearnerProfile
import com.example.learnedge.domain.ProfileErrors
import com.example.learnedge.domain.ProfileValidation
import com.example.learnedge.domain.Track
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val loading: Boolean = true,
    val isEdit: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val trackId: String? = null,
    val interests: String = "",
    val ratings: Map<String, Int> = emptyMap(),
    val dailyMinutesText: String = "60",
    val deadline: LocalDate? = null,
    val errors: ProfileErrors = ProfileErrors(),
    val saving: Boolean = false,
    /** Set once the profile is saved: true means the diagnostic quiz must run next. */
    val savedNeedsDiagnostic: Boolean? = null,
) {
    val selectedTrack: Track? get() = tracks.firstOrNull { it.id == trackId }
}

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val tracks = container.curriculum.tracks()
            val existing = container.profiles.get()
            _state.update {
                if (existing == null) {
                    it.copy(loading = false, tracks = tracks)
                } else {
                    it.copy(
                        loading = false,
                        isEdit = true,
                        tracks = tracks,
                        trackId = existing.trackId,
                        interests = existing.interests,
                        ratings = existing.selfRatings,
                        dailyMinutesText = existing.dailyMinutes.toString(),
                        deadline = existing.deadline,
                    )
                }
            }
        }
    }

    fun selectTrack(trackId: String) = edit { s ->
        val track = s.tracks.first { it.id == trackId }
        // Keep ratings the learner already gave; new skills start at 0.
        s.copy(trackId = trackId, ratings = track.skills.associate { it.id to (s.ratings[it.id] ?: 0) })
    }

    fun setInterests(text: String) = edit { it.copy(interests = text.take(MAX_INTERESTS_LENGTH)) }

    fun setRating(skillId: String, rating: Int) = edit { it.copy(ratings = it.ratings + (skillId to rating.coerceIn(0, 10))) }

    fun setDailyMinutes(text: String) = edit { it.copy(dailyMinutesText = text.filter(Char::isDigit).take(4)) }

    fun setDeadline(date: LocalDate) = edit { it.copy(deadline = date) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val track = s.selectedTrack
        val minutes = s.dailyMinutesText.toIntOrNull()
        val errors = ProfileValidation.validate(track, s.ratings, minutes, s.deadline, LocalDate.now())
        if (!errors.isValid || track == null || minutes == null || s.deadline == null) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val previous = container.profiles.get()
            // A finished diagnostic stays valid unless the learner switched to a different track.
            val keepDiagnostic = previous?.diagnosticCompleted == true && previous.trackId == track.id
            container.profiles.save(
                LearnerProfile(
                    trackId = track.id,
                    interests = s.interests,
                    selfRatings = track.skills.associate { it.id to (s.ratings[it.id] ?: 0) },
                    dailyMinutes = minutes,
                    deadline = s.deadline,
                    diagnosticCompleted = keepDiagnostic,
                )
            )
            // A changed deadline, budget or rating changes the plan; a new track waits for its diagnostic.
            if (keepDiagnostic) container.plans.regenerate(PlanRepository.Mode.FROM_TODAY)
            _state.update { it.copy(saving = false, savedNeedsDiagnostic = !keepDiagnostic) }
        }
    }

    /** Applies a field change and clears old validation messages. */
    private fun edit(change: (OnboardingUiState) -> OnboardingUiState) {
        _state.update { change(it).copy(errors = ProfileErrors()) }
    }

    private companion object {
        const val MAX_INTERESTS_LENGTH = 300
    }
}
