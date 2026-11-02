package com.example.learnedge.ui.diagnostic

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticScreen(onFinished: () -> Unit, viewModel: DiagnosticViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (state.results == null) "Diagnostic quiz" else "Your starting point") }) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val results = state.results
            when {
                state.loading || state.saving -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                results != null -> Results(results, onFinished)
                else -> Question(state, onSelect = viewModel::select, onNext = viewModel::next)
            }
        }
    }
}

@Composable
private fun Question(state: DiagnosticUiState, onSelect: (Int) -> Unit, onNext: () -> Unit) {
    val question = state.current ?: return
    val skillName = state.track?.skill(question.skillId)?.name.orEmpty()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Question ${state.index + 1} of ${state.questions.size} · $skillName", style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(
            progress = { state.index.toFloat() / state.questions.size },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(question.question, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
        question.options.forEachIndexed { i, option ->
            val selected = state.selected == i
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(i) },
                colors = if (selected) {
                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                } else {
                    CardDefaults.cardColors()
                },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = selected, onClick = { onSelect(i) })
                    Text(option, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Text(
            "Not sure? Pick your best guess. The quiz only sets your starting point.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onNext, enabled = state.selected != null, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.isLast) "Finish" else "Next")
        }
    }
}

@Composable
private fun Results(results: List<SkillResult>, onFinished: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Estimated mastery per skill, from your self-rating and quiz answers (Bayesian Knowledge Tracing).",
            style = MaterialTheme.typography.bodyMedium,
        )
        results.forEach { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(r.skillName, style = MaterialTheme.typography.titleSmall)
                        Text(String.format(Locale.US, "%.1f / 10", r.estimatedLevel), style = MaterialTheme.typography.titleSmall)
                    }
                    LinearProgressIndicator(
                        progress = { (r.estimatedLevel / 10).toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Quiz ${r.correct}/${r.asked} correct · you rated ${r.selfRating}/10 · target ${r.targetLevel}/10",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) { Text("See my plan") }
    }
}
