package com.example.learnedge.ui.today

import android.text.format.DateUtils
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.example.learnedge.data.tips.TipsProgress
import com.example.learnedge.llm.tips.Tip
import com.example.learnedge.llm.tips.TipSource
import com.example.learnedge.ui.plan.StudyItemUi
import com.example.learnedge.ui.plan.WhyDialog
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(onEditProfile: () -> Unit, onOpenTopic: (String) -> Unit, viewModel: TodayViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val night by viewModel.night.collectAsStateWithLifecycle()
    var whyItem by remember { mutableStateOf<StudyItemUi?>(null) }
    whyItem?.let { WhyDialog(it, whenText = "today") { whyItem = null } }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Today") },
                actions = { TextButton(onClick = onEditProfile) { Text("Edit profile") } },
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Greeting(state) }
            if (state.items.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            state.nextStudyDay?.let { "Nothing is planned for today. Your next study day is ${it.format(DAY_FORMAT)}." }
                                ?: "Nothing is planned for today.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            } else {
                item { FocusCard(state, onRegenerate = viewModel::regenerateTips) }
            }
            item { PreparedCard(night, onPrepare = viewModel::prepareNow, onToggle = viewModel::setNightShift) }
            items(state.items, key = { it.id }) { item ->
                StudyCard(
                    item,
                    tip = state.tips?.tips?.get(item.topicId),
                    onOpen = { onOpenTopic(item.topicId) },
                    onWhy = { whyItem = item },
                    onCheckedChange = { viewModel.setDone(item.id, it) },
                )
            }
        }
    }
}

@Composable
private fun Greeting(state: TodayUiState) {
    val hour = LocalTime.now().hour
    val greeting = when {
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(greeting, style = MaterialTheme.typography.headlineMedium)
        Text(
            "${state.date.format(DAY_FORMAT)} · ${state.trackName}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.totalMinutes > 0) {
            Text("${state.doneMinutes} of ${state.totalMinutes} minutes done today", style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(progress = { state.doneMinutes.toFloat() / state.totalMinutes }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FocusCard(state: TodayUiState, onRegenerate: () -> Unit) {
    val tips = state.tips
    val progress = state.tipsProgress
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Today's focus", style = MaterialTheme.typography.titleMedium)
                tips?.let { SourceLabel(it.summarySource) }
            }
            when {
                progress.running -> GeneratingStatus(progress)
                tips != null -> Text(tips.summary, style = MaterialTheme.typography.bodyMedium)
                else -> Text("Preparing today's tips…", style = MaterialTheme.typography.bodyMedium)
            }
            if (!progress.running && tips != null && tips.summarySource == TipSource.RULE_BASED) {
                TextButton(onClick = onRegenerate, contentPadding = PaddingValues(0.dp)) { Text("Ask the AI again") }
            }
        }
    }
}

@Composable
private fun GeneratingStatus(progress: TipsProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (progress.waitingForModel) "Waiting for the on-device model…" else "AI is writing today's tips · attempt ${progress.attempt} of ${progress.maxAttempts}",
            style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** How many of the next two days' lessons are ready, with "Prepare now" and the Night Shift switch. */
@Composable
private fun PreparedCard(night: NightUi, onPrepare: () -> Unit, onToggle: (Boolean) -> Unit) {
    val progress = night.progress
    val summary = night.summary
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Prepared for you", style = MaterialTheme.typography.titleMedium)
            if (progress.running) {
                Text(
                    "Preparing ${minOf(progress.done + 1, progress.total)} of ${progress.total}: ${progress.current}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                if (progress.note.isNotEmpty()) Text(progress.note, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    if (summary.total == 0) "Nothing is planned for the next two days." else "${summary.ready} of ${summary.total} lessons are ready to open instantly.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    summary.preparedAt?.let { "Last prepared ${DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}" }
                        ?: "Not prepared yet.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                FilledTonalButton(onClick = onPrepare, enabled = !progress.running && summary.ready < summary.total) { Text("Prepare now") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Night Shift", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = night.enabled, onCheckedChange = onToggle)
                }
            }
            Text(
                if (night.enabled) "While the phone charges overnight, the tutor prepares your next lessons." else "Night Shift is off: lessons are written when you open them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun SourceLabel(source: TipSource) {
    val ai = source == TipSource.AI
    Surface(
        color = if (ai) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (ai) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(if (ai) "AI" else "Rule-based", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun Chip(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun StudyCard(item: StudyItemUi, tip: Tip?, onOpen: () -> Unit, onWhy: () -> Unit, onCheckedChange: (Boolean) -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = if (item.done) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = item.done, onCheckedChange = onCheckedChange)
            Column(Modifier.padding(top = 10.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    item.topicName,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("${item.minutes} min")
                    if (item.skillName.isNotEmpty()) Chip(item.skillName)
                    item.mastery?.let { Chip("Mastery ${(it * 100).toInt()}%") }
                }
                if (tip != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                        SourceLabel(tip.source)
                        Text(tip.text, style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (item.description.isNotEmpty()) {
                    Text(item.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onOpen) { Text("Open lesson") }
                    TextButton(onClick = onWhy) { Text("Why today?") }
                }
            }
        }
    }
}
