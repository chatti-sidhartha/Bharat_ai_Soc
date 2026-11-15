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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.learnedge.appContainer
import com.example.learnedge.data.lessons.LessonProgress
import com.example.learnedge.llm.lessons.BundledLesson
import com.example.learnedge.llm.lessons.Lesson
import com.example.learnedge.llm.lessons.LessonInput
import com.example.learnedge.llm.lessons.LessonSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LessonUiState(
    val loading: Boolean = true,
    val topicName: String = "",
    val skillName: String = "",
    val minutes: Int = 0,
    val keyPoints: List<String> = emptyList(),
    /** Hand-written questions waiting in the bank for the quiz. */
    val questionCount: Int = 0,
    /** The AI lesson if one was written, otherwise the bundled text. */
    val lesson: Lesson? = null,
    /** Set while the model is writing this topic's lesson. */
    val writing: LessonProgress? = null,
    val input: LessonInput? = null,
)

class LessonViewModel(app: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(app) {
    private val container = app.appContainer
    private val topicId: String = checkNotNull(savedStateHandle["topicId"]) { "lesson route needs a topicId" }

    val state: StateFlow<LessonUiState> = combine(
        container.profiles.observe(),
        container.lessons.observe(topicId),
        container.lessons.progress,
    ) { profile, aiLesson, progress ->
        if (profile == null) return@combine LessonUiState(loading = false)
        val track = container.curriculum.track(profile.trackId)
        val topic = track.graph.topic(topicId)
        val content = container.content.content(topicId)
        val input = LessonInput(
            topicId = topicId,
            topicName = topic.name,
            skillName = track.skill(topic.skillId).name,
            trackName = track.name,
            description = topic.description,
            keyPoints = content?.keyPoints.orEmpty(),
        )
        LessonUiState(
            loading = false,
            topicName = topic.name,
            skillName = input.skillName,
            minutes = topic.estimatedMinutes,
            keyPoints = input.keyPoints,
            questionCount = content?.questions?.size ?: 0,
            lesson = aiLesson ?: BundledLesson.build(input),
            writing = progress.takeIf { it.topicId == topicId },
            input = input,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LessonUiState())

    init {
        // The lesson is written in the background; the screen shows the bundled text meanwhile.
        viewModelScope.launch {
            val input = state.first { !it.loading }.input ?: return@launch
            container.lessons.ensure(input)
        }
    }

    /** Asks the model again, e.g. after the bundled text was used because the model failed. */
    fun askAiAgain() {
        state.value.input?.let { container.lessons.ensure(it, force = true) }
    }
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
        if (state.loading) {
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
                    "${state.skillName} · ${state.minutes} min",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.lesson?.let { lesson -> item { LessonCard(lesson, state.writing, onAskAgain = viewModel::askAiAgain) } }
            state.lesson?.example?.takeIf { it.isNotBlank() }?.let { example -> item { ExampleCard(example) } }
            if (state.keyPoints.isNotEmpty()) item { KeyPointsCard(state.keyPoints) }
            item {
                Text(
                    if (state.questionCount > 0) "Question bank: ${state.questionCount} practice questions ready." else "No practice questions for this topic yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LessonCard(lesson: Lesson, writing: LessonProgress?, onAskAgain: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lesson", style = MaterialTheme.typography.titleSmall)
                SourceLabel(if (lesson.source == LessonSource.AI) "AI" else "Bundled", highlighted = lesson.source == LessonSource.AI)
            }
            Text(lesson.explanation, style = MaterialTheme.typography.bodyMedium)
            when {
                writing != null -> {
                    Text(
                        if (writing.waitingForModel) {
                            "Waiting for the on-device model…"
                        } else {
                            "AI is writing this lesson · attempt ${writing.attempt} of ${writing.maxAttempts} · ${writing.streamedChars} characters"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                lesson.source == LessonSource.BUNDLED ->
                    Button(onClick = onAskAgain) { Text("Ask the AI to write this lesson") }
            }
        }
    }
}

@Composable
private fun ExampleCard(example: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Example", style = MaterialTheme.typography.titleSmall)
            Text(example, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
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
