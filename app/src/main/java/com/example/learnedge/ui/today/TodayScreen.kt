package com.example.learnedge.ui.today

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import com.example.learnedge.ui.plan.detailLine
import java.time.format.DateTimeFormatter

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(onEditProfile: () -> Unit, onOpenTopic: (String) -> Unit, viewModel: TodayViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DayHeader(state) }
            if (state.items.isEmpty()) {
                item {
                    Text(
                        state.nextStudyDay?.let { "Nothing planned for today. Next study day: ${it.format(DAY_FORMAT)}." }
                            ?: "Nothing planned for today.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                item { SummaryCard(state, onRegenerate = viewModel::regenerateTips) }
            }
            items(state.items, key = { it.id }) { item ->
                TodayItem(
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
private fun DayHeader(state: TodayUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(state.date.format(DAY_FORMAT), style = MaterialTheme.typography.headlineSmall)
        Text(state.trackName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.totalMinutes > 0) {
            Text("${state.doneMinutes} of ${state.totalMinutes} min done", style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(
                progress = { state.doneMinutes.toFloat() / state.totalMinutes },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SummaryCard(state: TodayUiState, onRegenerate: () -> Unit) {
    val tips = state.tips
    val progress = state.tipsProgress
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Today's focus", style = MaterialTheme.typography.titleSmall)
                tips?.let { SourceLabel(it.summarySource) }
            }
            when {
                progress.running -> GeneratingStatus(progress)
                tips != null -> Text(tips.summary, style = MaterialTheme.typography.bodyMedium)
                else -> Text("Preparing today's tips…", style = MaterialTheme.typography.bodyMedium)
            }
            if (!progress.running && tips != null && (tips.summarySource == TipSource.RULE_BASED)) {
                TextButton(onClick = onRegenerate, contentPadding = PaddingValues(0.dp)) { Text("Ask the AI again") }
            }
        }
    }
}

@Composable
private fun GeneratingStatus(progress: TipsProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (progress.waitingForModel) {
                "Waiting for the on-device model…"
            } else {
                "AI is writing today's tips · attempt ${progress.attempt} of ${progress.maxAttempts} · ${progress.streamedChars} characters"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(Modifier.fillMaxWidth())
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
private fun TodayItem(item: StudyItemUi, tip: Tip?, onOpen: () -> Unit, onWhy: () -> Unit, onCheckedChange: (Boolean) -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = if (item.done) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = item.done, onCheckedChange = onCheckedChange)
            Column(Modifier.padding(top = 10.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    item.topicName,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                )
                Text(item.detailLine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (tip != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                        SourceLabel(tip.source)
                        Text(tip.text, style = MaterialTheme.typography.bodyMedium)
                    }
                } else if (item.description.isNotEmpty()) {
                    Text(item.description, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onWhy, contentPadding = PaddingValues(0.dp)) { Text("Why today?") }
            }
        }
    }
}
