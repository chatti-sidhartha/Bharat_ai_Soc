package com.example.learnedge.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.learnedge.domain.Track
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private const val MILLIS_PER_DAY = 86_400_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onSaved: (needsDiagnostic: Boolean) -> Unit,
    onCancel: (() -> Unit)?,
    viewModel: OnboardingViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.savedNeedsDiagnostic) {
        state.savedNeedsDiagnostic?.let(onSaved)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEdit) "Edit learning profile" else "Welcome! Set your goal") },
                actions = { onCancel?.let { TextButton(onClick = it) { Text("Cancel") } } },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("1. What do you want to become?") {
                state.tracks.forEach { track ->
                    TrackCard(track, selected = track.id == state.trackId, onClick = { viewModel.selectTrack(track.id) })
                }
                ErrorText(state.errors.track)
            }

            Section("2. Interests (optional)") {
                OutlinedTextField(
                    value = state.interests,
                    onValueChange = viewModel::setInterests,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("e.g. building web apps, cricket statistics, games") },
                    minLines = 2,
                )
            }

            state.selectedTrack?.let { track ->
                Section("3. Rate your current level (0 = never used it, 10 = expert)") {
                    track.skills.forEach { skill ->
                        val rating = state.ratings[skill.id] ?: 0
                        Text("${skill.name}: $rating / 10", style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = rating.toFloat(),
                            onValueChange = { viewModel.setRating(skill.id, it.roundToInt()) },
                            valueRange = 0f..10f,
                            steps = 9,
                        )
                    }
                    ErrorText(state.errors.ratings)
                }
            }

            Section("4. How much can you study each day?") {
                OutlinedTextField(
                    value = state.dailyMinutesText,
                    onValueChange = viewModel::setDailyMinutes,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Minutes per day") },
                    suffix = { Text("min") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = state.errors.dailyMinutes != null,
                )
                ErrorText(state.errors.dailyMinutes)
            }

            Section("5. When is your deadline?") {
                DeadlineField(state.deadline, onPicked = viewModel::setDeadline)
                ErrorText(state.errors.deadline)
            }

            Button(onClick = viewModel::save, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.isEdit) "Save" else "Save and take the diagnostic quiz")
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun TrackCard(track: Track, selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Column {
                Text(track.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${track.skills.size} skills · ${track.graph.topics.size} topics",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ErrorText(message: String?) {
    message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeadlineField(deadline: LocalDate?, onPicked: (LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Text(deadline?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) ?: "Pick a date")
    }
    if (!open) return

    val tomorrowEpochDay = LocalDate.now().plusDays(1).toEpochDay()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = deadline?.toEpochDay()?.times(MILLIS_PER_DAY),
        selectableDates = object : SelectableDates {
            // The picker works in UTC midnight millis; only dates after today can be chosen.
            override fun isSelectableDate(utcTimeMillis: Long) = Math.floorDiv(utcTimeMillis, MILLIS_PER_DAY) >= tomorrowEpochDay
        },
    )
    DatePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(
                onClick = {
                    pickerState.selectedDateMillis?.let { onPicked(LocalDate.ofEpochDay(Math.floorDiv(it, MILLIS_PER_DAY))) }
                    open = false
                },
                enabled = pickerState.selectedDateMillis != null,
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    ) {
        DatePicker(pickerState)
    }
}
