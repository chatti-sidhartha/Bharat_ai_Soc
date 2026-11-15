package com.example.learnedge.ui.quiz

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.example.learnedge.data.quiz.AnswerResult
import com.example.learnedge.data.quiz.QuizSession
import com.example.learnedge.domain.BankQuestion
import com.example.learnedge.domain.Bkt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface QuizUi {
    data object Loading : QuizUi

    data class NoQuestions(val topicName: String) : QuizUi

    data class Asking(val topicName: String, val number: Int, val total: Int, val question: BankQuestion) : QuizUi

    data class Feedback(
        val topicName: String,
        val number: Int,
        val total: Int,
        val question: BankQuestion,
        val chosenIndex: Int,
        val result: AnswerResult,
        val reported: Boolean = false,
        /** Mastery after the reported question's effect was removed. */
        val masteryAfterReport: Double? = null,
    ) : QuizUi {
        val isLast: Boolean get() = number == total
    }

    data class Summary(
        val topicName: String,
        val before: Double,
        val after: Double,
        val correct: Int,
        val answered: Int,
        val planUpdated: Boolean,
    ) : QuizUi {
        val mastered: Boolean get() = Bkt.isMastered(after)
    }
}

class QuizViewModel(app: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(app) {
    private val container = app.appContainer
    private val topicId: String = checkNotNull(savedStateHandle["topicId"]) { "quiz route needs a topicId" }

    private val _ui = MutableStateFlow<QuizUi>(QuizUi.Loading)
    val ui: StateFlow<QuizUi> = _ui.asStateFlow()

    private var topicName = topicId
    private lateinit var session: QuizSession
    private var index = 0
    private var busy = false
    private var planRebuildStarted = false

    /** Question id -> was it answered correctly, for the score on the summary. */
    private val outcomes = LinkedHashMap<String, Boolean>()
    private val reported = HashSet<String>()

    init {
        viewModelScope.launch {
            val profile = container.profiles.observe().first()
            if (profile != null) topicName = container.curriculum.track(profile.trackId).graph.topic(topicId).name
            session = container.quiz.startSession(topicId)
            _ui.value = if (session.questions.isEmpty()) QuizUi.NoQuestions(topicName) else asking()
        }
    }

    private fun asking() = QuizUi.Asking(topicName, index + 1, session.questions.size, session.questions[index])

    fun choose(optionIndex: Int) {
        val asking = _ui.value as? QuizUi.Asking ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            val result = container.quiz.answer(asking.question, optionIndex)
            outcomes[asking.question.id] = result.correct
            _ui.value = QuizUi.Feedback(asking.topicName, asking.number, asking.total, asking.question, optionIndex, result)
            busy = false
        }
    }

    /** Hides the question from future quizzes and takes its answer out of the topic's mastery. */
    fun report() {
        val feedback = _ui.value as? QuizUi.Feedback ?: return
        if (feedback.reported || busy) return
        busy = true
        viewModelScope.launch {
            val restored = container.quiz.report(feedback.question.id)
            reported += feedback.question.id
            _ui.value = feedback.copy(reported = true, masteryAfterReport = restored)
            busy = false
        }
    }

    fun next() {
        val feedback = _ui.value as? QuizUi.Feedback ?: return
        if (!feedback.isLast) {
            index++
            _ui.value = asking()
            return
        }
        viewModelScope.launch {
            val counted = outcomes.filterKeys { it !in reported }
            val after = container.quiz.currentMastery(topicId)
            _ui.value = QuizUi.Summary(topicName, session.startMastery, after, counted.count { it.value }, counted.size, planUpdated = false)
            rebuildPlan()
        }
    }

    /** Runs in the app scope, so leaving the screen right away does not cancel it. */
    private fun rebuildPlan() {
        if (planRebuildStarted) return
        planRebuildStarted = true
        container.appScope.launch {
            container.quiz.finish()
            _ui.update { (it as? QuizUi.Summary)?.copy(planUpdated = true) ?: it }
        }
    }

    override fun onCleared() {
        // Mastery changes with every answer; if the learner leaves mid-quiz the plan must still follow.
        if (outcomes.isNotEmpty()) rebuildPlan()
    }
}

private fun pct(p: Double) = "${Math.round(p * 100)}%"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(onBack: () -> Unit, viewModel: QuizViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quiz") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        val body = Modifier.padding(padding).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
        when (val state = ui) {
            QuizUi.Loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is QuizUi.NoQuestions -> Column(body) { Text("There are no quiz questions for ${state.topicName} yet.") }
            is QuizUi.Asking -> Column(body, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Header(state.topicName, state.number, state.total)
                Text(state.question.question, style = MaterialTheme.typography.titleMedium)
                state.question.options.forEachIndexed { i, option ->
                    OutlinedButton(onClick = { viewModel.choose(i) }, modifier = Modifier.fillMaxWidth()) {
                        Text(option, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            is QuizUi.Feedback -> Column(body, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Header(state.topicName, state.number, state.total)
                Text(state.question.question, style = MaterialTheme.typography.titleMedium)
                state.question.options.forEachIndexed { i, option ->
                    OptionResult(option, correct = i == state.question.answerIndex, chosen = i == state.chosenIndex)
                }
                FeedbackCard(state)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = viewModel::report, enabled = !state.reported) { Text("Report this question") }
                    Button(onClick = viewModel::next) { Text(if (state.isLast) "See results" else "Next") }
                }
            }
            is QuizUi.Summary -> Column(body, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.topicName, style = MaterialTheme.typography.headlineSmall)
                Text("Quiz complete: ${state.correct} of ${state.answered} correct", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Mastery", style = MaterialTheme.typography.titleSmall)
                        Text("${pct(state.before)} → ${pct(state.after)}", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            if (state.mastered) "Topic mastered ✓" else "Not mastered yet: ${pct(Bkt.MASTERY_THRESHOLD)} marks a topic mastered.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Text(
                    if (state.planUpdated) "Your plan was rebuilt from tomorrow." else "Updating your plan…",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onBack) { Text("Back to lesson") }
            }
        }
    }
}

@Composable
private fun Header(topicName: String, number: Int, total: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$topicName · question $number of $total", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LinearProgressIndicator(progress = { number.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun OptionResult(text: String, correct: Boolean, chosen: Boolean) {
    val colors = when {
        correct -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        chosen -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else -> CardDefaults.cardColors()
    }
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Text(
            (if (correct) "✓ " else if (chosen) "✗ " else "") + text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun FeedbackCard(state: QuizUi.Feedback) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (state.result.correct) "Correct ✓" else "Not quite ✗", style = MaterialTheme.typography.titleSmall)
            Text(state.question.explanation, style = MaterialTheme.typography.bodyMedium)
            Text("Mastery ${pct(state.result.pLBefore)} → ${pct(state.result.pLAfter)}", style = MaterialTheme.typography.bodySmall)
            if (state.reported) {
                Text(
                    "Reported. This question is hidden from future quizzes and its effect on your mastery was removed" +
                        (state.masteryAfterReport?.let { " (mastery now ${pct(it)})" } ?: "") + ".",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
