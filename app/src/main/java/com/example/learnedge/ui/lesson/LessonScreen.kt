package com.example.learnedge.ui.lesson

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.learnedge.appContainer
import com.example.learnedge.data.content.Explanation
import com.example.learnedge.data.content.ExplanationStatus
import com.example.learnedge.domain.ContentSource
import com.example.learnedge.domain.LevelBand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LessonUiState(
    val loading: Boolean = true,
    val topicName: String = "",
    val skillName: String = "",
    val minutes: Int = 0,
    val keyPoints: List<String> = emptyList(),
    /** Hand-written questions waiting in the bank for the quiz. */
    val questionCount: Int = 0,
    val explanation: Explanation? = null,
)

class LessonViewModel(app: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(app) {
    private val container = app.appContainer
    private val topicId: String = checkNotNull(savedStateHandle["topicId"]) { "lesson route needs a topicId" }

    val state: StateFlow<LessonUiState> = combine(
        container.profiles.observe(),
        container.content.getExplanation(topicId),
    ) { profile, explanation ->
        if (profile == null) return@combine LessonUiState(loading = false)
        val track = container.curriculum.track(profile.trackId)
        val topic = track.graph.topic(topicId)
        LessonUiState(
            loading = false,
            topicName = topic.name,
            skillName = track.skill(topic.skillId).name,
            minutes = topic.estimatedMinutes,
            keyPoints = topic.keyPoints,
            questionCount = container.questionBank.count(topicId),
            explanation = explanation,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LessonUiState())

    /** Asks the model for a fresh explanation at a higher temperature. */
    fun explainDifferently() = container.content.explainDifferently(topicId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LessonScreen(onBack: () -> Unit, viewModel: LessonViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.topicName.ifEmpty { "Lesson" }) },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        val explanation = state.explanation
        if (state.loading || explanation == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "${state.skillName} · ${state.minutes} min · level: ${explanation.band.label()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { ExplanationCard(explanation) }
            // The bundled explanation already is the key points, so they are only listed separately next to AI text.
            if (explanation.source == ContentSource.AI && state.keyPoints.isNotEmpty()) item { KeyPointsCard(state.keyPoints) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = viewModel::explainDifferently,
                        enabled = explanation.status == ExplanationStatus.READY,
                    ) { Text("Explain it differently") }
                    // The quiz arrives in the next milestone.
                    Button(onClick = {}, enabled = false) { Text("Take quiz") }
                }
            }
            item {
                Text(
                    if (state.questionCount > 0) "Question bank: ${state.questionCount} questions ready for the quiz." else "No quiz questions for this topic yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun LevelBand.label() = name.lowercase()

@Composable
private fun ExplanationCard(explanation: Explanation) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Explanation", style = MaterialTheme.typography.titleSmall)
                val ai = explanation.source == ContentSource.AI
                SourceLabel(if (ai) "AI" else "Bundled", highlighted = ai)
            }
            Text(explanation.text, style = MaterialTheme.typography.bodyMedium)
            when (explanation.status) {
                ExplanationStatus.WAITING_FOR_MODEL -> {
                    Text("Waiting for the on-device model…", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                ExplanationStatus.WRITING -> {
                    Text(
                        "AI is writing · attempt ${explanation.attempt} of ${explanation.maxAttempts}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                ExplanationStatus.READY -> explanation.lastFailure?.let {
                    Text(
                        "The AI text was rejected ($it), so the hand-written key points are shown.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun KeyPointsCard(points: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Key points", style = MaterialTheme.typography.titleSmall)
                SourceLabel("Hand-written", highlighted = false)
            }
            points.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun SourceLabel(text: String, highlighted: Boolean) {
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
