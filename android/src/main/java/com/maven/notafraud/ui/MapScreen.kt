@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maven.notafraud.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.Area
import com.maven.notafraud.core.model.Goal
import com.maven.notafraud.core.model.Project
import com.maven.notafraud.core.model.ProjectKind
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskStatus
import com.maven.notafraud.core.plan.Heat
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.store.Actions
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

enum class NodeType { ROOT, GOAL, AREA, PROJECT, TASK }

/** One box on the map. [x]/[y] are dp from the map centre. */
data class MapNode(
    val key: String,
    val type: NodeType,
    val label: String,
    val sub: String,
    val parent: String?,
    val extraParents: List<String> = emptyList(),
    val x: Float = 0f,
    val y: Float = 0f,
    val refId: String? = null,
    val pseudo: Boolean = false,
    val hot: Boolean = false,
    val picked: Boolean = false,
)

private const val NO_GOAL = "goal:none"
private const val NO_AREA = "area:none"
private const val NO_PROJECT = "project:none"

/**
 * Radial mind map: you in the middle, goals around you, the areas that feed each goal, their
 * projects, and (for an opened project) its tasks. An area that feeds several goals sits under
 * its first goal and draws dashed lines to the others.
 */
object MapLayout {
    private val RADII = floatArrayOf(0f, 240f, 500f, 760f, 1000f)

    fun build(d: AppData, expanded: Set<String>, now: Long, todayPicks: Set<String>): List<MapNode> {
        val zone = Time.zone(d.settings.zoneId)
        val goals = d.goals.sortedBy { it.order }
        val goalIds = goals.map { it.id }.toSet()
        val areas = d.areas.filterNot { it.archived }.sortedBy { it.order }
        val projects = d.projects.filterNot { it.archived }
        val open = d.tasks.filter { it.status == TaskStatus.TODO }
        val areaIds = areas.map { it.id }.toSet()
        val projectIds = projects.map { it.id }.toSet()

        val primaryGoal = areas.associate { a -> a.id to (goals.firstOrNull { it.id in a.goalIds }?.id ?: NO_GOAL) }
        val projectsByArea = projects.groupBy { if (it.areaId in areaIds) it.areaId!! else NO_AREA }
        val tasksByProject = open.groupBy { if (it.projectId in projectIds) it.projectId!! else NO_PROJECT }
        val needsNoArea = projectsByArea.containsKey(NO_AREA) || tasksByProject.containsKey(NO_PROJECT)
        val needsNoGoal = areas.any { primaryGoal[it.id] == NO_GOAL } || needsNoArea

        // Tree (children by key).
        val children = LinkedHashMap<String, MutableList<MapNode>>()
        fun add(n: MapNode) { children.getOrPut(n.parent ?: "") { mutableListOf() } += n }

        add(MapNode("root", NodeType.ROOT, "You", "", null))
        for (g in goals) {
            val n = areas.count { g.id in it.goalIds }
            add(MapNode("goal:${g.id}", NodeType.GOAL, g.name, "$n area${if (n == 1) "" else "s"}", "root", refId = g.id))
        }
        if (needsNoGoal) add(MapNode(NO_GOAL, NodeType.GOAL, "Not linked to a goal", "tap to sort", "root", pseudo = true))

        fun projectNode(p: Project?, parentKey: String) {
            val key = if (p == null) NO_PROJECT else "project:${p.id}"
            val list = tasksByProject[p?.id ?: NO_PROJECT].orEmpty()
            val next = list.mapNotNull { it.dueAt }.minOrNull()
            val sub = buildString {
                append("${list.size} open")
                next?.let { append(" · next ${dueText(it, now, zone)}") }
            }
            add(
                MapNode(
                    key, NodeType.PROJECT, p?.name ?: "No project", sub, parentKey, refId = p?.id, pseudo = p == null,
                    hot = list.any { it.hot || Heat.deadlineHot(it, now) },
                )
            )
            if (key in expanded) for (t in list.sortedBy { it.dueAt ?: Long.MAX_VALUE }) {
                val tsub = listOfNotNull("${t.estMin - t.loggedMin.coerceAtMost(t.estMin)}m", t.dueAt?.let { dueText(it, now, zone) }).joinToString(" · ")
                add(MapNode("task:${t.id}", NodeType.TASK, t.title, tsub, key, refId = t.id, hot = t.hot || Heat.deadlineHot(t, now), picked = t.id in todayPicks))
            }
        }

        for (a in areas) {
            val pk = primaryGoal[a.id]!!
            val parent = if (pk == NO_GOAL) NO_GOAL else "goal:$pk"
            val ps = projectsByArea[a.id].orEmpty()
            val openCount = ps.sumOf { tasksByProject[it.id]?.size ?: 0 }
            add(
                MapNode(
                    "area:${a.id}", NodeType.AREA, a.name, "${ps.size} project${if (ps.size == 1) "" else "s"} · $openCount open", parent,
                    extraParents = a.goalIds.filter { it != pk && it in goalIds }.map { "goal:$it" }, refId = a.id,
                )
            )
            for (p in ps.sortedBy { it.name }) projectNode(p, "area:${a.id}")
        }
        if (needsNoArea) {
            add(MapNode(NO_AREA, NodeType.AREA, "No area", "projects to sort", NO_GOAL, pseudo = true))
            for (p in projectsByArea[NO_AREA].orEmpty()) projectNode(p, NO_AREA)
            if (tasksByProject.containsKey(NO_PROJECT)) projectNode(null, NO_AREA)
        }

        // Weights = leaves under each node, so busy branches get more room.
        val weight = HashMap<String, Int>()
        fun w(key: String): Int = weight.getOrPut(key) { max(1, children[key].orEmpty().sumOf { w(it.key) }) }
        w("root")

        val placed = mutableListOf<MapNode>()
        fun place(n: MapNode, depth: Int, a0: Double, a1: Double) {
            val mid = (a0 + a1) / 2
            val r = RADII[depth.coerceAtMost(RADII.size - 1)]
            placed += n.copy(x = (r * cos(mid)).toFloat(), y = (r * sin(mid)).toFloat())
            val kids = children[n.key].orEmpty()
            val total = kids.sumOf { w(it.key) }.toDouble()
            var a = a0
            for (k in kids) {
                val span = (a1 - a0) * w(k.key) / total
                place(k, depth + 1, a, a + span)
                a += span
            }
        }
        place(children[""]!!.first(), 0, -PI / 2, 3 * PI / 2)
        return placed
    }
}

private fun nodeWidth(t: NodeType): Dp = when (t) {
    NodeType.ROOT -> 96.dp
    NodeType.GOAL -> 176.dp
    NodeType.AREA -> 150.dp
    NodeType.PROJECT -> 150.dp
    NodeType.TASK -> 150.dp
}

private fun nodeColor(n: MapNode): Color = when {
    n.pseudo -> Palette.textDim
    n.type == NodeType.ROOT -> Palette.green
    n.type == NodeType.GOAL -> Palette.amber
    n.type == NodeType.AREA -> Color(0xFF6EC8FF)
    n.type == NodeType.PROJECT -> Palette.green
    else -> Palette.text
}

private const val CANVAS_DP = 2600f

@Composable
fun MapView(data: AppData, now: Long, onEditTask: (Task) -> Unit, onNewTask: (projectId: String?) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    val today = Time.dateOf(now, Time.zone(data.settings.zoneId))
    val nodes = remember(data, expanded, now / 60_000) { MapLayout.build(data, expanded, now, data.picks[today.toString()].orEmpty().toSet()) }
    val byKey = nodes.associateBy { it.key }
    val density = LocalDensity.current

    var scale by remember { mutableFloatStateOf(0.55f) }
    var offset by remember { mutableStateOf<Offset?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(Palette.bg)) {
        val vw = with(density) { maxWidth.toPx() }
        val vh = with(density) { maxHeight.toPx() }
        val centerPx = with(density) { (CANVAS_DP / 2).dp.toPx() }
        val o = offset ?: Offset(vw / 2, vh / 2)

        fun focus(n: MapNode) {
            val px = with(density) { Offset(n.x.dp.toPx(), n.y.dp.toPx()) }
            offset = Offset(vw / 2 - px.x * scale, vh / 2 - px.y * scale)
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val cur = offset ?: Offset(size.width / 2f, size.height / 2f)
                        val ns = (scale * zoom).coerceIn(0.18f, 2.4f)
                        val z = ns / scale
                        offset = centroid + (cur - centroid) * z + pan
                        scale = ns
                    }
                }
        ) {
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredSize(CANVAS_DP.dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = scale; scaleY = scale
                        translationX = o.x - centerPx * scale
                        translationY = o.y - centerPx * scale
                    }
            ) {
                Canvas(Modifier.requiredSize(CANVAS_DP.dp)) {
                    val c = CANVAS_DP / 2
                    fun p(n: MapNode) = Offset((c + n.x).dp.toPx(), (c + n.y).dp.toPx())
                    for (n in nodes) {
                        val parent = n.parent?.let(byKey::get) ?: continue
                        drawLine(nodeColor(n).copy(alpha = 0.45f), p(parent), p(n), strokeWidth = 2.dp.toPx())
                        for (ep in n.extraParents) {
                            val e = byKey[ep] ?: continue
                            drawLine(
                                nodeColor(n).copy(alpha = 0.35f), p(e), p(n), strokeWidth = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                            )
                        }
                    }
                }
                for (n in nodes) {
                    val w = nodeWidth(n.type)
                    val col = nodeColor(n)
                    Column(
                        Modifier
                            .offset((CANVAS_DP / 2 + n.x).dp - w / 2, (CANVAS_DP / 2 + n.y).dp - 26.dp)
                            .width(w)
                            .background(if (selected == n.key) Palette.surfaceHigh else Palette.surface, RoundedCornerShape(12.dp))
                            .border(BorderStroke(if (n.hot) 2.dp else 1.dp, if (n.hot) Palette.red else col.copy(alpha = 0.7f)), RoundedCornerShape(12.dp))
                            .clickable {
                                selected = n.key
                                if (n.type == NodeType.PROJECT) expanded = if (n.key in expanded) expanded - n.key else expanded + n.key
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            (if (n.picked) "★ " else "") + n.label,
                            color = col, fontWeight = if (n.type == NodeType.GOAL || n.type == NodeType.ROOT) FontWeight.Bold else FontWeight.SemiBold,
                            fontSize = if (n.type == NodeType.GOAL) 15.sp else 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        )
                        if (n.sub.isNotBlank()) Text(n.sub, color = Palette.textDim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallFloatingActionButton(onClick = { scale = (scale * 1.3f).coerceAtMost(2.4f) }) { Text("+") }
            SmallFloatingActionButton(onClick = { scale = (scale / 1.3f).coerceAtLeast(0.18f) }) { Text("–") }
            SmallFloatingActionButton(onClick = { scale = 0.45f; offset = null }) { Text("⌂") }
        }
        Text(
            "Pinch to zoom · tap a project to open its tasks · dashed = area feeds that goal too",
            style = MaterialTheme.typography.labelSmall, color = Palette.textDim,
            modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
        )

        selected?.let { key ->
            byKey[key]?.let { n ->
                NodeSheet(
                    n, data, now,
                    onDismiss = { selected = null },
                    onSelect = { k -> byKey[k]?.let { selected = k; focus(it) } ?: run { selected = null } },
                    onExpand = { k -> expanded = expanded + k },
                    onEditTask = { t -> selected = null; onEditTask(t) },
                    onNewTask = { pid -> selected = null; onNewTask(pid) },
                )
            }
        }
    }
}

@Composable
private fun NodeSheet(
    n: MapNode, d: AppData, now: Long,
    onDismiss: () -> Unit, onSelect: (String) -> Unit, onExpand: (String) -> Unit,
    onEditTask: (Task) -> Unit, onNewTask: (String?) -> Unit,
) {
    val ctx = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var rename by remember(n.key) { mutableStateOf<String?>(null) }
    var adding by remember(n.key) { mutableStateOf<NodeType?>(null) }

    fun change(fn: (AppData) -> AppData) = Graph.changeAndReplan(ctx, fn)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel(n.type.name.lowercase())
            Text(n.label, style = MaterialTheme.typography.titleLarge, color = nodeColor(n))
            if (n.sub.isNotBlank()) Text(n.sub, color = Palette.textDim)

            when (n.type) {
                NodeType.ROOT -> {
                    Text("Goals are what a life well spent looks like. Areas feed them; projects live in areas; tasks live in projects.", color = Palette.textDim)
                    OutlinedButton(onClick = { adding = NodeType.GOAL }) { Text("Add goal") }
                }

                NodeType.GOAL -> {
                    val goal = d.goals.firstOrNull { it.id == n.refId }
                    val areas = d.areas.filter { !it.archived && (if (goal == null) it.goalIds.isEmpty() else goal.id in it.goalIds) }
                    SectionLabel("Areas feeding this")
                    areas.forEach { a -> TextButton(onClick = { onSelect("area:${a.id}") }) { Text(a.name) } }
                    if (n.pseudo) {
                        Text("These need a goal (areas) or an area (projects). Open one and link it.", color = Palette.textDim)
                        TextButton(onClick = { onSelect(NO_AREA) }) { Text("No area") }
                    }
                    if (goal != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { adding = NodeType.AREA }) { Text("Add area") }
                            OutlinedButton(onClick = { rename = goal.name }) { Text("Rename") }
                            TextButton(onClick = { change { Actions.deleteGoal(it, goal.id) }; onDismiss() }) { Text("Delete", color = Palette.red) }
                        }
                    }
                }

                NodeType.AREA -> {
                    val area = d.areas.firstOrNull { it.id == n.refId }
                    if (area != null) {
                        SectionLabel("Feeds these goals")
                        d.goals.sortedBy { it.order }.forEach { g ->
                            Row(Modifier.fillMaxWidth().clickable {
                                change { Actions.upsertArea(it, area.copy(goalIds = if (g.id in area.goalIds) area.goalIds - g.id else area.goalIds + g.id)) }
                            }, verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = g.id in area.goalIds, onCheckedChange = null)
                                Text(g.name, maxLines = 2)
                            }
                        }
                    }
                    SectionLabel("Projects")
                    d.projects.filter { !it.archived && (if (area == null) d.areas.none { a -> a.id == it.areaId } else it.areaId == area.id) }
                        .forEach { p -> TextButton(onClick = { onExpand("project:${p.id}"); onSelect("project:${p.id}") }) { Text(p.name) } }
                    if (area != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { adding = NodeType.PROJECT }) { Text("Add project") }
                        OutlinedButton(onClick = { rename = area.name }) { Text("Rename") }
                        TextButton(onClick = { change { Actions.upsertArea(it, area.copy(archived = true)) }; onDismiss() }) { Text("Archive", color = Palette.textDim) }
                    }
                }

                NodeType.PROJECT -> {
                    val p = d.projects.firstOrNull { it.id == n.refId }
                    if (p != null) {
                        SectionLabel("Area")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            d.areas.filterNot { it.archived }.sortedBy { it.order }.forEach { a ->
                                FilterChip(p.areaId == a.id, { change { Actions.upsertProject(it, p.copy(areaId = a.id)) } }, { Text(a.name) })
                            }
                        }
                        SectionLabel("Kind")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ProjectKind.entries.forEach { k ->
                                FilterChip(p.kind == k, { change { Actions.upsertProject(it, p.copy(kind = k)) } }, { Text(k.name.lowercase()) })
                            }
                        }
                    }
                    SectionLabel("Open tasks")
                    d.tasks.filter { it.status == TaskStatus.TODO && it.projectId == p?.id }.sortedBy { it.dueAt ?: Long.MAX_VALUE }.forEach { t ->
                        Row(Modifier.fillMaxWidth().clickable { onEditTask(t) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(false, { Graph.changeAndReplan(ctx) { Actions.setDone(it, t.id, true, Graph.now()) } })
                            Text(t.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            t.dueAt?.let { Text(dueText(it, now, Time.zone(d.settings.zoneId)), color = Palette.textDim, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onNewTask(p?.id) }) { Text("Add task") }
                        if (p != null) {
                            OutlinedButton(onClick = { rename = p.name }) { Text("Rename") }
                            TextButton(onClick = { change { Actions.upsertProject(it, p.copy(archived = true)) }; onDismiss() }) { Text("Archive", color = Palette.textDim) }
                        }
                    }
                }

                NodeType.TASK -> {
                    val t = d.tasks.firstOrNull { it.id == n.refId }
                    if (t != null) {
                        Text(listOfNotNull("${t.loggedMin}/${t.estMin} min", t.priority.name.lowercase(), if (t.hot) "pinned hot" else null).joinToString(" · "), color = Palette.textDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onEditTask(t) }) { Text("Edit") }
                            OutlinedButton(onClick = { Graph.changeAndReplan(ctx) { Actions.setDone(it, t.id, true, Graph.now()) }; onDismiss() }) { Text("Done") }
                            OutlinedButton(onClick = { Graph.update { Actions.startSession(it, t.id, null, Graph.now()) }; Graph.refresh(ctx); onDismiss() }) { Text("Start") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    rename?.let { current ->
        NameDialog("Rename", current, onDismiss = { rename = null }) { name ->
            when (n.type) {
                NodeType.GOAL -> d.goals.firstOrNull { it.id == n.refId }?.let { g -> change { Actions.upsertGoal(it, g.copy(name = name)) } }
                NodeType.AREA -> d.areas.firstOrNull { it.id == n.refId }?.let { a -> change { Actions.upsertArea(it, a.copy(name = name)) } }
                NodeType.PROJECT -> d.projects.firstOrNull { it.id == n.refId }?.let { p -> change { Actions.upsertProject(it, p.copy(name = name)) } }
                else -> Unit
            }
            rename = null
        }
    }

    adding?.let { type ->
        when (type) {
            NodeType.GOAL -> NameDialog("New goal", "", onDismiss = { adding = null }) { name ->
                change { Actions.upsertGoal(it, Goal(name = name)) }; adding = null
            }
            NodeType.AREA -> AddAreaDialog(d, n.refId!!, onDismiss = { adding = null }) { adding = null }
            NodeType.PROJECT -> NameDialog("New project in ${n.label}", "", onDismiss = { adding = null }) { name ->
                change { Actions.upsertProject(it, Project(name = name, kind = ProjectKind.PROJECT, areaId = n.refId)) }; adding = null
            }
            else -> adding = null
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Link an existing area to this goal, or create a new one. */
@Composable
private fun AddAreaDialog(d: AppData, goalId: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add area") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("New area name") }, modifier = Modifier.fillMaxWidth())
                val others = d.areas.filter { !it.archived && goalId !in it.goalIds }
                if (others.isNotEmpty()) {
                    SectionLabel("Or link an existing area")
                    others.forEach { a ->
                        TextButton(onClick = { Graph.changeAndReplan(ctx) { Actions.upsertArea(it, a.copy(goalIds = a.goalIds + goalId)) }; onDone() }) { Text(a.name) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { Graph.changeAndReplan(ctx) { Actions.upsertArea(it, Area(name = name.trim(), goalIds = setOf(goalId))) }; onDone() }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
