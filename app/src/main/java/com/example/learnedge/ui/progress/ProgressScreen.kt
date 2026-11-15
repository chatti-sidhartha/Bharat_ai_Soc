package com.example.learnedge.ui.progress

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.learnedge.appContainer
import com.example.learnedge.domain.Bkt
import com.example.learnedge.domain.DayMinutes
import com.example.learnedge.domain.GoalProgress
import com.example.learnedge.domain.LoggedItem
import com.example.learnedge.domain.MasteryTimeline
import com.example.learnedge.domain.SkillGaps
import com.example.learnedge.domain.StudyLog
import com.example.learnedge.domain.TimelinePoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class SkillBar(val name: String, val level: Double, val target: Int) {
    val fraction: Float get() = if (target <= 0) 1f else (level / target).coerceIn(0.0, 1.0).toFloat()
}

data class ProgressUi(
    val loading: Boolean = true,
    val trackName: String = "",
    /** 0..1 share of the weighted skill targets reached. */
    val overall: Double = 0.0,
    val masteredTopics: Int = 0,
    val totalTopics: Int = 0,
    val skills: List<SkillBar> = emptyList(),
    val week: List<DayMinutes> = emptyList(),
    val dailyBudget: Int = 0,
    val timeline: List<TimelinePoint> = emptyList(),
)

class ProgressViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer

    val state: StateFlow<ProgressUi> = combine(
        container.profiles.observe(),
        container.learnerState.observe(),
        container.plans.observeItems(),
        container.quiz.observeAttempts(),
        container.quiz.observeReported(),
    ) { profile, mastery, items, attempts, reported ->
        if (profile == null) return@combine ProgressUi(loading = false)
        val track = container.curriculum.track(profile.trackId)
        val masteryValues = mastery.mapValues { it.value.mastery }
        val gaps = SkillGaps.compute(track, masteryValues, profile.selfRatings).values
        val zone = ZoneId.systemDefault()
        val logged = items.filter { it.topicId in track.graph }.map { item ->
            LoggedItem(
                plannedDay = item.dayEpochDay?.let(LocalDate::ofEpochDay),
                doneDay = item.doneAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() },
                minutes = item.minutes,
            )
        }
        ProgressUi(
            loading = false,
            trackName = track.name,
            overall = GoalProgress.overall(gaps),
            masteredTopics = track.graph.topics.count { Bkt.isMastered(masteryValues[it.id] ?: 0.0) },
            totalTopics = track.graph.topics.size,
            skills = gaps.map { SkillBar(it.skill.name, it.currentLevel, it.skill.targetLevel) },
            week = StudyLog.lastDays(LocalDate.now(), 7, logged),
            dailyBudget = profile.dailyMinutes,
            timeline = MasteryTimeline.build(attempts.filter { it.topicId in track.graph }, reported, zone),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUi())
}

private fun pct(value: Double) = "${Math.round(value * 100)}%"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(onOpenMap: () -> Unit, viewModel: ProgressViewModel = viewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Progress") }) }) { padding ->
        if (ui.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { OverallCard(ui) }
            item { Text("Skills: current level vs target", style = MaterialTheme.typography.titleMedium) }
            items(ui.skills, key = { it.name }) { SkillRow(it) }
            item { WeekChart(ui.week, ui.dailyBudget) }
            item { MasteryChart(ui.timeline) }
            item { Button(onClick = onOpenMap, modifier = Modifier.fillMaxWidth()) { Text("Open knowledge map") } }
        }
    }
}

@Composable
private fun OverallCard(ui: ProgressUi) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Progress to your goal: ${ui.trackName}", style = MaterialTheme.typography.titleSmall)
            Text(pct(ui.overall), style = MaterialTheme.typography.headlineMedium)
            LinearProgressIndicator(progress = { ui.overall.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("${ui.masteredTopics} of ${ui.totalTopics} topics mastered", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SkillRow(skill: SkillBar) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(skill.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${String.format(Locale.US, "%.1f", skill.level)} / ${skill.target}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LinearProgressIndicator(progress = { skill.fraction }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WeekChart(days: List<DayMinutes>, budget: Int) {
    val measurer = rememberTextMeasurer()
    val doneColor = MaterialTheme.colorScheme.primary
    val plannedColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val maxValue = maxOf(budget, days.maxOfOrNull { maxOf(it.doneMinutes, it.plannedMinutes) } ?: 0, 1).toFloat()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Last 7 days: minutes done vs planned", style = MaterialTheme.typography.titleSmall)
            Canvas(Modifier.fillMaxWidth().height(170.dp)) {
                val labelHeight = 20.dp.toPx()
                val chartHeight = size.height - labelHeight - 12.dp.toPx()
                val top = 12.dp.toPx()
                val slot = size.width / days.size
                val barWidth = slot * 0.28f
                val gap = 2.dp.toPx()
                val baseline = top + chartHeight
                // The daily time budget, as a dashed line.
                val budgetY = baseline - chartHeight * (budget / maxValue)
                drawLine(
                    labelColor.copy(alpha = 0.6f), Offset(0f, budgetY), Offset(size.width, budgetY),
                    strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
                )
                days.forEachIndexed { i, day ->
                    val center = slot * i + slot / 2
                    val plannedHeight = chartHeight * day.plannedMinutes / maxValue
                    val doneHeight = chartHeight * day.doneMinutes / maxValue
                    if (plannedHeight > 0f) {
                        drawRect(plannedColor, Offset(center - barWidth - gap, baseline - plannedHeight), Size(barWidth, plannedHeight), style = Stroke(1.5.dp.toPx()))
                    }
                    if (doneHeight > 0f) {
                        drawRect(doneColor, Offset(center + gap, baseline - doneHeight), Size(barWidth, doneHeight))
                        drawText(measurer, "${day.doneMinutes}", Offset(center + gap, baseline - doneHeight - 12.dp.toPx()), style = TextStyle(fontSize = 9.sp, color = labelColor))
                    }
                    val name = day.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
                    drawText(measurer, name, Offset(center - 12.dp.toPx(), baseline + 4.dp.toPx()), style = TextStyle(fontSize = 10.sp, color = labelColor))
                }
            }
            Text(
                "Filled bar: done. Outline: planned. Dashed line: your daily budget ($budget min). " +
                    "Past days only keep the blocks you completed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MasteryChart(points: List<TimelinePoint>) {
    val measurer = rememberTextMeasurer()
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mastery over time", style = MaterialTheme.typography.titleSmall)
            if (points.size < 2) {
                Text("Take a quiz to start this chart: it shows the average mastery of the topics you were quizzed on.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            Text(
                "Average of ${points.last().topicsQuizzed} quizzed topic(s): ${pct(points.first().averageMastery)} at the start → ${pct(points.last().averageMastery)} now",
                style = MaterialTheme.typography.bodyMedium,
            )
            Canvas(Modifier.fillMaxWidth().height(150.dp)) {
                val left = 34.dp.toPx()
                val top = 8.dp.toPx()
                val plotWidth = size.width - left - 8.dp.toPx()
                val plotHeight = size.height - top - 8.dp.toPx()
                fun y(value: Double) = top + plotHeight * (1f - value.toFloat().coerceIn(0f, 1f))
                listOf(0.0, 0.5, 1.0).forEach { level ->
                    drawLine(gridColor, Offset(left, y(level)), Offset(left + plotWidth, y(level)), strokeWidth = 1.dp.toPx())
                    drawText(measurer, pct(level), Offset(0f, y(level) - 7.dp.toPx()), style = TextStyle(fontSize = 9.sp, color = labelColor))
                }
                val xs = points.indices.map { left + plotWidth * it / (points.size - 1) }
                val path = Path().apply {
                    points.forEachIndexed { i, p -> if (i == 0) moveTo(xs[i], y(p.averageMastery)) else lineTo(xs[i], y(p.averageMastery)) }
                }
                drawPath(path, lineColor, style = Stroke(2.dp.toPx()))
                points.forEachIndexed { i, p ->
                    drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(xs[i], y(p.averageMastery)))
                }
            }
            Text(
                "First point: before your first quiz. Then one point per day with quizzes.",
                style = MaterialTheme.typography.bodySmall,
                color = labelColor,
            )
        }
    }
}
