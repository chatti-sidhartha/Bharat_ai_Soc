package com.example.learnedge.ui.tutor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import com.example.learnedge.llm.benchmark.BenchmarkPrompts
import com.example.learnedge.llm.benchmark.PromptSummary
import com.example.learnedge.llm.benchmark.Stat
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.learnedge.llm.GenerationStats
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val busy = state.isGenerating || state.engineStatus == EngineStatus.Loading

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Tutor") },
                    actions = {
                        OverflowMenu(
                            canBenchmark = state.engineStatus == EngineStatus.Ready && !state.isGenerating,
                            onBenchmark = viewModel::runBenchmark,
                        )
                    },
                )
                EngineSelector(state.engineKind, enabled = !busy, onSelect = viewModel::selectEngine)
                if (state.engineStatus == EngineStatus.Loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            InputBar(
                isGenerating = state.isGenerating,
                canSend = state.engineStatus == EngineStatus.Ready,
                onSend = viewModel::send,
                onStop = viewModel::stop,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            EngineStatusBanner(state)
            MessageList(state.messages, state.isGenerating, Modifier.weight(1f))
        }
    }

    state.benchmark?.let {
        BenchmarkDialog(it, onStop = viewModel::stopBenchmark, onDismiss = viewModel::dismissBenchmark)
    }
}

@Composable
private fun OverflowMenu(canBenchmark: Boolean, onBenchmark: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Benchmark") },
                enabled = canBenchmark,
                onClick = {
                    expanded = false
                    onBenchmark()
                },
            )
        }
    }
}

@Composable
private fun BenchmarkDialog(benchmark: BenchmarkUiState, onStop: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!benchmark.running) onDismiss() },
        title = { Text("Benchmark · ${benchmark.engineLabel}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "${BenchmarkPrompts.ALL.size} prompts × ${BenchmarkPrompts.RUNS_PER_PROMPT} runs · " +
                        "${benchmark.completedRuns}/${benchmark.totalRuns} done",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (benchmark.running) {
                    LinearProgressIndicator(
                        progress = { benchmark.completedRuns.toFloat() / benchmark.totalRuns },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    benchmark.currentRun?.let { Text("Running: $it", style = MaterialTheme.typography.bodySmall) }
                }
                benchmark.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                }
                benchmark.summaries.forEach { SummaryTable(it) }
                if (!benchmark.running && benchmark.completedRuns > 0) {
                    Text(
                        "Values are mean (min–max). Saved to ${benchmark.csvPath}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (benchmark.running) {
                TextButton(onClick = onStop) { Text("Stop") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun SummaryTable(summary: PromptSummary) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(
            "${summary.promptLabel} · ${summary.runs} run${if (summary.runs == 1) "" else "s"}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        SummaryRow("TTFT (ms)", summary.timeToFirstTokenMs, 0)
        SummaryRow("Decode (tok/s)", summary.decodeTokensPerSecond, 1)
        SummaryRow("Tokens", summary.totalTokens, 0)
        SummaryRow("Total (ms)", summary.totalTimeMs, 0)
        SummaryRow("Peak PSS (MB)", summary.peakPssMb, 0)
        SummaryRow("Battery (°C)", summary.batteryTempC, 1)
    }
}

@Composable
private fun SummaryRow(label: String, stat: Stat?, decimals: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(stat?.format(decimals) ?: "–", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EngineSelector(selected: EngineKind, enabled: Boolean, onSelect: (EngineKind) -> Unit) {
    Row(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EngineKind.entries.forEach { kind ->
            FilterChip(
                selected = selected == kind,
                onClick = { onSelect(kind) },
                enabled = enabled,
                label = { Text(kind.label) },
            )
        }
    }
}

@Composable
private fun EngineStatusBanner(state: ChatUiState) {
    val text = when (val status = state.engineStatus) {
        EngineStatus.Loading -> "Loading ${state.engineKind.label} model…"
        EngineStatus.Ready -> "${state.engineKind.label} ready · model loaded in ${state.loadTimeMs ?: 0} ms"
        is EngineStatus.Failed -> status.message
    }
    val isError = state.engineStatus is EngineStatus.Failed
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = if (isError) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(12.dp)) {
            if (isError) {
                Text(
                    "${state.engineKind.label} engine unavailable",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>, isGenerating: Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    // Keep the newest text visible while tokens stream in.
    val lastLength = messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(messages.size, lastLength) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
    }

    if (messages.isEmpty()) {
        Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Ask something, e.g. \"How should I start learning Spring Boot?\"")
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            val isLast = message.id == messages.last().id
            MessageBubble(message, waiting = isLast && isGenerating && message.text.isEmpty())
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, waiting: Boolean) {
    val alignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    val color = when {
        message.error != null -> MaterialTheme.colorScheme.errorContainer
        message.fromUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(color = color, shape = MaterialTheme.shapes.medium, modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(12.dp)) {
                when {
                    waiting -> Text("Thinking…", style = MaterialTheme.typography.bodyMedium)
                    message.text.isNotEmpty() -> Text(message.text.trimStart())
                }
                message.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.onErrorContainer) }
                message.stats?.let { StatsBar(it) }
            }
        }
    }
}

@Composable
private fun StatsBar(stats: GenerationStats) {
    val ttft = stats.timeToFirstTokenMs?.let { "$it ms" } ?: "–"
    val speed = String.format(Locale.US, "%.1f", stats.tokensPerSecond)
    val total = String.format(Locale.US, "%.1f", stats.totalTimeMs / 1000.0)
    Text(
        "TTFT $ttft · $speed tok/s · ${total}s · ${stats.tokenCount} tok",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun InputBar(isGenerating: Boolean, canSend: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var input by remember { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Message") },
            maxLines = 4,
        )
        if (isGenerating) {
            OutlinedButton(onClick = onStop) { Text("Stop") }
        } else {
            Button(
                onClick = {
                    onSend(input)
                    input = ""
                },
                enabled = canSend && input.isNotBlank(),
            ) { Text("Send") }
        }
    }
}
