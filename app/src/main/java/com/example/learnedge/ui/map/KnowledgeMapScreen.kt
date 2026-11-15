package com.example.learnedge.ui.map

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.learnedge.appContainer
import com.example.learnedge.domain.GraphLayout
import com.example.learnedge.domain.MapPosition
import com.example.learnedge.domain.NodeState
import com.example.learnedge.domain.NodeStates
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MapNodeUi(val id: String, val name: String, val state: NodeState, val mastery: Double, val position: MapPosition)

/** What the detail sheet shows for one topic. */
data class TopicSheetUi(
    val id: String,
    val name: String,
    val skillName: String,
    val state: NodeState,
    val mastery: Double,
    val attempts: Int,
    val correct: Int,
    val prerequisites: List<Pair<String, NodeState>>,
    val unlocks: Int,
)

data class MapUi(
    val loading: Boolean = true,
    val trackName: String = "",
    val nodes: List<MapNodeUi> = emptyList(),
    /** (prerequisite id, dependent id) */
    val edges: List<Pair<String, String>> = emptyList(),
    val details: Map<String, TopicSheetUi> = emptyMap(),
)

class KnowledgeMapViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.appContainer

    val state: StateFlow<MapUi> = combine(container.profiles.observe(), container.learnerState.observe()) { profile, progress ->
        if (profile == null) return@combine MapUi(loading = false)
        val track = container.curriculum.track(profile.trackId)
        val graph = track.graph
        val mastery = progress.mapValues { it.value.mastery }
        val positions = GraphLayout.layout(graph)
        val states = graph.topics.associate { it.id to NodeStates.of(it.id, graph, mastery) }
        MapUi(
            loading = false,
            trackName = track.name,
            nodes = graph.topics.map { MapNodeUi(it.id, it.name, states.getValue(it.id), mastery[it.id] ?: 0.0, positions.getValue(it.id)) },
            edges = graph.topics.flatMap { topic -> graph.getPrerequisites(topic.id).map { it to topic.id } },
            details = graph.topics.associate { topic ->
                topic.id to TopicSheetUi(
                    id = topic.id,
                    name = topic.name,
                    skillName = track.skill(topic.skillId).name,
                    state = states.getValue(topic.id),
                    mastery = mastery[topic.id] ?: 0.0,
                    attempts = progress[topic.id]?.attempts ?: 0,
                    correct = progress[topic.id]?.correct ?: 0,
                    prerequisites = graph.getPrerequisites(topic.id).map { graph.topic(it).name to states.getValue(it) },
                    unlocks = graph.descendantCount(topic.id),
                )
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUi())
}

private fun NodeState.color(): Color = when (this) {
    NodeState.LOCKED -> Color(0xFF55555F)
    NodeState.WEAK -> Color(0xFF9C3F3F)
    NodeState.LEARNING -> Color(0xFF9A7620)
    NodeState.STRONG -> Color(0xFF2E6A9B)
    NodeState.MASTERED -> Color(0xFF2F8050)
}

private fun pct(value: Double) = "${Math.round(value * 100)}%"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeMapScreen(onBack: () -> Unit, onOpenLesson: (String) -> Unit, viewModel: KnowledgeMapViewModel = viewModel()) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Knowledge map") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹ Back") } },
            )
        },
    ) { padding ->
        if (ui.loading) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) { MapCanvas(ui, selected) { selected = it } }
            Legend()
        }
    }
    selected?.let { id ->
        ui.details[id]?.let { detail ->
            ModalBottomSheet(onDismissRequest = { selected = null }) {
                SheetContent(detail, onOpenLesson = { selected = null; onOpenLesson(id) })
            }
        }
    }
}

@Composable
private fun MapCanvas(ui: MapUi, selected: String?, onTap: (String?) -> Unit) {
    val density = LocalDensity.current.density
    val nodeWidth = 150f * density
    val nodeHeight = 56f * density
    val gapX = 56f * density
    val gapY = 16f * density
    val pad = 16f * density
    val measurer = rememberTextMeasurer()
    val edgeColor = MaterialTheme.colorScheme.outline
    val highlight = MaterialTheme.colorScheme.primary

    var scale by remember { mutableFloatStateOf(0.6f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun rectOf(node: MapNodeUi) = Rect(
        pad + node.position.layer * (nodeWidth + gapX),
        pad + node.position.row * (nodeHeight + gapY),
        pad + node.position.layer * (nodeWidth + gapX) + nodeWidth,
        pad + node.position.row * (nodeHeight + gapY) + nodeHeight,
    )

    val byId = ui.nodes.associateBy { it.id }
    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Pinch to zoom around the fingers, drag to pan.
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(0.25f, 3f)
                    offset = centroid - (centroid - offset) * (newScale / scale) + pan
                    scale = newScale
                }
            }
            .pointerInput(ui.nodes) {
                detectTapGestures { tap ->
                    val mapPoint = (tap - offset) / scale
                    onTap(ui.nodes.firstOrNull { rectOf(it).contains(mapPoint) }?.id)
                }
            },
    ) {
        withTransform({
            translate(offset.x, offset.y)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            ui.edges.forEach { (from, to) ->
                val a = rectOf(byId.getValue(from))
                val b = rectOf(byId.getValue(to))
                val start = Offset(a.right, a.center.y)
                val end = Offset(b.left, b.center.y)
                val bend = (end.x - start.x) / 2
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    cubicTo(start.x + bend, start.y, end.x - bend, end.y, end.x, end.y)
                }
                val active = selected == from || selected == to
                drawPath(path, if (active) highlight else edgeColor.copy(alpha = 0.55f), style = Stroke((if (active) 2.5f else 1.5f) * density))
            }
            ui.nodes.forEach { node ->
                val rect = rectOf(node)
                val corner = CornerRadius(10f * density)
                drawRoundRect(node.state.color(), rect.topLeft, rect.size, corner)
                // Locked nodes get a dashed border and the selected node a bright one, so state never relies on colour alone.
                when {
                    node.id == selected -> drawRoundRect(Color.White, rect.topLeft, rect.size, corner, style = Stroke(3f * density))
                    node.state == NodeState.LOCKED -> drawRoundRect(
                        Color.White.copy(alpha = 0.7f), rect.topLeft, rect.size, corner,
                        style = Stroke(1.5f * density, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                    )
                }
                val inner = 8f * density
                drawText(
                    measurer, node.name,
                    topLeft = Offset(rect.left + inner, rect.top + 6f * density),
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                    size = Size(nodeWidth - 2 * inner, nodeHeight * 0.6f),
                )
                drawText(
                    measurer, "${node.state.symbol} ${node.state.label} · ${pct(node.mastery)}",
                    topLeft = Offset(rect.left + inner, rect.bottom - 20f * density),
                    style = TextStyle(fontSize = 10.sp, color = Color.White.copy(alpha = 0.92f)),
                    overflow = TextOverflow.Clip,
                    maxLines = 1,
                    size = Size(nodeWidth - 2 * inner, 16f * density),
                )
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NodeState.entries.forEach { state ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(14.dp).background(state.color(), MaterialTheme.shapes.extraSmall))
                Text("${state.symbol} ${state.label}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SheetContent(detail: TopicSheetUi, onOpenLesson: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(detail.name, style = MaterialTheme.typography.headlineSmall)
        Text("${detail.state.symbol} ${detail.state.label} · ${detail.skillName}", style = MaterialTheme.typography.titleMedium)
        Text("Mastery P(L): ${pct(detail.mastery)}", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { detail.mastery.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text("Answers recorded: ${detail.attempts} (${detail.correct} correct)", style = MaterialTheme.typography.bodyMedium)
        Text("Prerequisites", style = MaterialTheme.typography.titleSmall)
        if (detail.prerequisites.isEmpty()) {
            Text("None", style = MaterialTheme.typography.bodyMedium)
        } else {
            detail.prerequisites.forEach { (name, state) -> Text("• $name · ${state.symbol} ${state.label}", style = MaterialTheme.typography.bodyMedium) }
        }
        Text(
            if (detail.unlocks == 0) "No later topic depends on it." else "Unlocks ${detail.unlocks} later topic${if (detail.unlocks == 1) "" else "s"}.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onOpenLesson, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp)) { Text("Open lesson") }
    }
}
