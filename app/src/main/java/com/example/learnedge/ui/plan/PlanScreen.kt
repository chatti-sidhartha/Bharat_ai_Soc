package com.example.learnedge.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE d MMM")
private val DEADLINE_FORMAT = DateTimeFormatter.ofPattern("d MMMM yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(onOpenTopic: (String) -> Unit, viewModel: PlanViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The item whose "Why this?" explanation is open, with where it sits in the plan ("today", "on Tue 17 Nov" or "" if deferred).
    var why by remember { mutableStateOf<Pair<StudyItemUi, String>?>(null) }
    why?.let { (item, whenText) -> WhyDialog(item, whenText) { why = null } }
    Scaffold(topBar = { TopAppBar(title = { Text("Study plan") }) }) { padding ->
        if (state.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Summary(state, onRegenerate = viewModel::regenerate) }
            items(state.days, key = { it.date.toEpochDay() }) { day ->
                val whenText = if (day.date == LocalDate.now()) "today" else "on ${day.date.format(DAY_FORMAT)}"
                DayCard(day, onOpenTopic, onWhy = { item -> why = item to whenText })
            }
            if (state.deferred.isNotEmpty()) {
                item {
                    Text("Deferred (${state.deferred.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                items(state.deferred, key = { it.id }) { DeferredCard(it, onWhy = { why = it to "" }) }
            }
        }
    }
}

@Composable
private fun Summary(state: PlanUiState, onRegenerate: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(state.trackName, style = MaterialTheme.typography.titleLarge)
        Text(
            "${state.topicCount} topics · ${formatMinutes(state.remainingMinutes)} left · ${state.dailyMinutes} min/day" +
                (state.deadline?.let { " · deadline ${it.format(DEADLINE_FORMAT)}" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!state.feasible) {
            Text(
                "Not everything fits before the deadline. ${state.deferred.size} topics are deferred; " +
                    "add study time or move the deadline to include them.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = onRegenerate, enabled = !state.regenerating, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.regenerating) "Regenerating…" else "Regenerate plan")
        }
    }
}

@Composable
private fun DayCard(day: PlanDayUi, onOpenTopic: (String) -> Unit, onWhy: (StudyItemUi) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(day.date.format(DAY_FORMAT), style = MaterialTheme.typography.titleSmall)
                Text("${day.minutes} min", style = MaterialTheme.typography.titleSmall)
            }
            HorizontalDivider()
            day.items.forEach { item ->
                Column(Modifier.fillMaxWidth().clickable { onOpenTopic(item.topicId) }) {
                    Text(
                        (if (item.done) "✓ " else "• ") + item.topicName,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    )
                    Text(item.detailLine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onWhy(item) }, contentPadding = PaddingValues(0.dp)) { Text("Why this?") }
            }
        }
    }
}

@Composable
private fun DeferredCard(item: StudyItemUi, onWhy: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.topicName, style = MaterialTheme.typography.titleSmall)
            Text(item.detailLine(), style = MaterialTheme.typography.bodySmall)
            item.deferredReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            TextButton(onClick = onWhy, contentPadding = PaddingValues(0.dp)) { Text("Why this?") }
        }
    }
}

private fun formatMinutes(minutes: Int): String = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
