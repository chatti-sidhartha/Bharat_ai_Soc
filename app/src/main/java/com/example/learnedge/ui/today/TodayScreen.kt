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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.learnedge.ui.plan.StudyItemUi
import com.example.learnedge.ui.plan.detailLine
import java.time.format.DateTimeFormatter

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(onEditProfile: () -> Unit, viewModel: TodayViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
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
            }
            items(state.items, key = { it.id }) { item ->
                TodayItem(item, onCheckedChange = { viewModel.setDone(item.id, it) })
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
private fun TodayItem(item: StudyItemUi, onCheckedChange: (Boolean) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = if (item.done) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item.done, onCheckedChange = onCheckedChange)
            Column(Modifier.padding(end = 8.dp)) {
                Text(
                    item.topicName,
                    style = MaterialTheme.typography.titleMedium,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                )
                Text(item.detailLine(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.description.isNotEmpty()) {
                    Text(item.description, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
