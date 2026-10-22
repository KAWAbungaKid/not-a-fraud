package com.maven.notafraud.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.Skill
import com.maven.notafraud.core.model.SkillTree
import com.maven.notafraud.core.plan.Practice
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.stats.Skills
import com.maven.notafraud.core.stats.Stat
import com.maven.notafraud.core.stats.Stats
import com.maven.notafraud.core.store.Actions

/** The "You" tab: Routine | Stats | Skills. */
@Composable
fun YouScreen(data: AppData, now: Long) {
    var seg by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            listOf("Routine", "Stats", "Skills").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = seg == i,
                    onClick = { seg = i },
                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                ) { Text(label) }
            }
        }
        Box(Modifier.weight(1f)) {
            when (seg) {
                0 -> RoutineScreen(data, now)
                1 -> StatsScreen(data, now)
                else -> SkillsScreen(data)
            }
        }
    }
}

// ---------------------------------------------------------------- Stats

fun statColor(score: Double?): Color = when {
    score == null -> Palette.textDim
    score < Stats.LOW -> Palette.red
    score < 6.5 -> Palette.amber
    else -> Palette.green
}

@Composable
fun StatsScreen(data: AppData, now: Long) {
    val zone = Time.zone(data.settings.zoneId)
    val today = Time.dateOf(now, zone)
    val stats = remember(data, today) { Stats.all(data, today) }
    val pushed = remember(data, now / 3_600_000) { Stats.mostPushed(data, now) }
    var open by remember { mutableStateOf<String?>(null) }
    var checkingIn by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Last 7 days, 1–10", style = MaterialTheme.typography.titleLarge)
            Text(
                if (data.settings.statsDriveImportance) "A stat under ${Stats.LOW.toInt()} makes its areas more important in the plan (a task's own importance still wins)."
                else "Stats are for looking only (Setup → Planning to let them steer the plan).",
                color = Palette.textDim, style = MaterialTheme.typography.bodySmall,
            )
        }
        items(stats, key = { it.key }) { st ->
            StatRow(st, expanded = open == st.key) { open = if (open == st.key) null else st.key }
        }
        item {
            OutlinedButton(onClick = { checkingIn = true }) { Text("Weekly check-in") }
        }
        if (pushed.isNotEmpty()) {
            item { SectionLabel("Pushed back most (14 days)") }
            items(pushed, key = { "p" + it.first.id }) { (t, n) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(t.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${n}×", style = Mono, color = Palette.textDim)
                }
            }
            item { Text("Just a pattern to notice. Nothing changes because of it.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (checkingIn) CheckInDialog(data, now) { checkingIn = false }
}

@Composable
private fun StatRow(st: Stat, expanded: Boolean, onClick: () -> Unit) {
    Panel(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(st.name, Modifier.width(96.dp), style = MaterialTheme.typography.titleMedium)
            Box(Modifier.weight(1f).height(12.dp).background(Palette.surfaceHigh, RoundedCornerShape(6.dp))) {
                val f = ((st.score ?: 0.0) / 10.0).toFloat().coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth(f).height(12.dp).background(statColor(st.score), RoundedCornerShape(6.dp)))
            }
            Spacer(Modifier.width(10.dp))
            Text(st.score?.let { "%.1f".format(it) } ?: "—", style = Mono, color = statColor(st.score))
            val tr = st.trend
            Text(
                when { tr == null -> "  "; tr > 0.3 -> " ▲"; tr < -0.3 -> " ▼"; else -> " ·" },
                color = when { tr == null -> Palette.textDim; tr > 0.3 -> Palette.green; tr < -0.3 -> Palette.red; else -> Palette.textDim },
            )
        }
        if (expanded) {
            for ((k, v) in st.parts) Row(Modifier.fillMaxWidth()) {
                Text(k, Modifier.weight(1f), color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                Text(v, style = MaterialTheme.typography.bodySmall)
            }
            st.prev?.let { Text("Week before: %.1f".format(it), color = Palette.textDim, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Sunday check-in: the stats the phone can't measure. */
@Composable
fun CheckInDialog(data: AppData, now: Long, onDismiss: () -> Unit) {
    val zone = Time.zone(data.settings.zoneId)
    val week = Practice.weekStart(Time.dateOf(now, zone))
    val existing = data.checkIns.firstOrNull { it.weekStart == week.toString() }?.ratings.orEmpty()
    val keys = listOf("social" to "Time with people", "spirit" to "Faith / spirit", "stress" to "Calm (5 = no stress)", "happy" to "Happiness")
    var r by remember { mutableStateOf(existing) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This week, 1–5") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((k, label) in keys) {
                    Text(label)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (v in 1..5) FilterChip(r[k] == v, { r = r + (k to v) }, { Text("$v") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Graph.update { Actions.checkIn(it, week, r, Graph.now()) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}

// ---------------------------------------------------------------- Skills

private val NODE_W = 104.dp
private val NODE_H = 64.dp
private val SLOT_W = 116.dp
private val ROW_H = 96.dp

/** Tidy layout: leaves take successive slots, a parent sits over the middle of its children. */
private fun layout(skills: List<Skill>): Map<String, Pair<Float, Int>> {
    val byParent = skills.groupBy { it.parentId?.takeIf { p -> skills.any { s -> s.id == p } } }
    val pos = HashMap<String, Pair<Float, Int>>()
    var next = 0f
    fun place(s: Skill, depth: Int): Float {
        val kids = byParent[s.id].orEmpty()
        val x = if (kids.isEmpty()) next.also { next += 1f } else kids.map { place(it, depth + 1) }.let { (it.first() + it.last()) / 2f }
        pos[s.id] = x to depth
        return x
    }
    for (root in byParent[null].orEmpty()) place(root, 0)
    return pos
}

@Composable
fun SkillsScreen(data: AppData) {
    val trees = data.skillTrees.sortedBy { it.order }
    var treeId by rememberSaveable { mutableStateOf(trees.firstOrNull()?.id) }
    val tree = trees.firstOrNull { it.id == treeId } ?: trees.firstOrNull()
    var selected by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<Skill?>(null) }
    var editingTree by remember { mutableStateOf<SkillTree?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (t in trees) FilterChip(t.id == tree?.id, { treeId = t.id; selected = null }, { Text(t.name + if (t.sport) " ⚑" else "") })
            TextButton(onClick = { editingTree = SkillTree(name = "", order = (trees.maxOfOrNull { it.order } ?: 0) + 1) }) { Text("+ Tree") }
        }
        if (tree == null) {
            Text("No skill trees yet. Add one: an instrument, a sport, a craft.", color = Palette.textDim)
            return@Column
        }
        val skills = data.skills.filter { it.treeId == tree.id }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (tree.sport) "Sport: 20+ min counts as a workout. " else "") + "Levels at 1, 5, 15, 40, 100 h.",
                Modifier.weight(1f), color = Palette.textDim, style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { editingTree = tree }) { Text("Edit") }
            TextButton(onClick = { adding = Skill(treeId = tree.id, name = "") }) { Text("+ Skill") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            SkillGraph(data, skills, selected) { selected = it }
        }
        selected?.let { id -> data.skills.firstOrNull { it.id == id } }?.let { s ->
            SkillPanel(data, s, onAddChild = { adding = Skill(treeId = tree.id, name = "", parentId = s.id) }, onClose = { selected = null })
        }
    }

    adding?.let { s -> SkillEditor(s, data.skills.any { it.id == s.id }, onDismiss = { adding = null }, onSave = { Graph.update { d -> Actions.upsertSkill(d, it) }; adding = null }, onDelete = {
        Graph.update { Actions.deleteSkill(it, s.id) }; adding = null; selected = null
    }) }
    editingTree?.let { t -> TreeEditor(t, data.skillTrees.any { it.id == t.id }, onDismiss = { editingTree = null }, onSave = {
        Graph.update { d -> Actions.upsertTree(d, it) }; treeId = it.id; editingTree = null
    }, onDelete = { Graph.update { Actions.deleteTree(it, t.id) }; treeId = null; editingTree = null }) }
}

@Composable
private fun SkillGraph(data: AppData, skills: List<Skill>, selected: String?, onSelect: (String) -> Unit) {
    val pos = remember(skills) { layout(skills) }
    if (pos.isEmpty()) { Text("Empty tree. Tap + Skill.", color = Palette.textDim, modifier = Modifier.padding(16.dp)); return }
    val cols = (pos.values.maxOf { it.first } + 1f)
    val rows = pos.values.maxOf { it.second } + 1
    val density = LocalDensity.current
    val lineColor = Palette.greenDim
    Box(Modifier.size(SLOT_W * cols, ROW_H * rows).padding(top = 8.dp)) {
        Canvas(Modifier.matchParentSizeCompat()) {
            for (s in skills) {
                val p = s.parentId?.let { pos[it] } ?: continue
                val c = pos[s.id] ?: continue
                with(density) {
                    val from = Offset((SLOT_W * p.first + NODE_W / 2).toPx(), (ROW_H * p.second + NODE_H).toPx())
                    val to = Offset((SLOT_W * c.first + NODE_W / 2).toPx(), (ROW_H * c.second).toPx())
                    drawLine(if (Skills.unlocked(data, s.id)) lineColor else Palette.surfaceHigh, from, to, strokeWidth = 3.dp.toPx())
                }
            }
        }
        for (s in skills) {
            val (x, depth) = pos[s.id] ?: continue
            val min = Skills.minutes(data, s.id)
            val lvl = Skills.level(min)
            val open = Skills.unlocked(data, s.id)
            val col = when { !open -> Palette.textDim; lvl == 0 -> Palette.amber; else -> Palette.green }
            Column(
                Modifier.offset(SLOT_W * x, ROW_H * depth).size(NODE_W, NODE_H)
                    .background(if (s.id == selected) Palette.surfaceHigh else Palette.surface, RoundedCornerShape(12.dp))
                    .border(if (s.id == selected) 2.dp else 1.dp, col, RoundedCornerShape(12.dp))
                    .clickable { onSelect(s.id) }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(s.name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = if (open) Palette.text else Palette.textDim)
                Text(if (open) "Lv $lvl" else "locked", style = Mono.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize), color = col)
            }
        }
    }
}

private fun Modifier.matchParentSizeCompat() = this.then(Modifier.fillMaxSize())

@Composable
private fun SkillPanel(data: AppData, s: Skill, onAddChild: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val min = Skills.minutes(data, s.id)
    val lvl = Skills.level(min)
    val open = Skills.unlocked(data, s.id)
    val task = s.practiceTaskId?.let { id -> data.tasks.firstOrNull { it.id == id } }
    var weekly by remember(s.id) { mutableStateOf(task?.weeklyTargetMin ?: 90) }
    var manual by remember(s.id) { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    Panel(Modifier.padding(vertical = 8.dp), highlight = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { editing = true }) { Text("Edit") }
            TextButton(onClick = onClose) { Text("Close") }
        }
        val next = Skills.LEVEL_HOURS.getOrNull(lvl + 1)
        Text("Level $lvl · ${Time.durationText(min)}" + (next?.let { " · next at ${it} h" } ?: " · maxed"), color = Palette.textDim)
        LinearProgressIndicator(progress = { Skills.progress(min).toFloat() }, modifier = Modifier.fillMaxWidth())
        if (!open) Text("Opens when ${data.skills.firstOrNull { it.id == s.parentId }?.name ?: "its parent"} reaches level 1. You can still practice it.", color = Palette.amber, style = MaterialTheme.typography.bodySmall)
        if (s.notes.isNotBlank()) Text(s.notes, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Weekly", style = MaterialTheme.typography.bodySmall)
            for (m in listOf(0, 60, 90, 180, 300)) FilterChip(weekly == m, { weekly = m }, { Text(if (m == 0) "off" else Time.durationText(m)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { Graph.changeAndReplan(ctx) { Actions.practiceSkill(it, s.id, weekly, Graph.now()) } }) {
                Text(if (task == null) "Plan practice" else "Update plan")
            }
            if (task != null) OutlinedButton(onClick = { Graph.update { Actions.startSession(it, task.id, null, Graph.now()) }; Graph.refresh(ctx) }) { Text("Start now") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(manual, { manual = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), singleLine = true, label = { Text("Add minutes done off the app") })
            TextButton(onClick = { manual.toIntOrNull()?.let { m -> Graph.update { Actions.addSkillMinutes(it, s.id, m) } }; manual = "" }) { Text("Add") }
        }
        TextButton(onClick = onAddChild) { Text("+ Skill that builds on this") }
    }
    if (editing) SkillEditor(s, true, onDismiss = { editing = false }, onSave = { Graph.update { d -> Actions.upsertSkill(d, it) }; editing = false }, onDelete = {
        Graph.update { Actions.deleteSkill(it, s.id) }; editing = false; onClose()
    })
}

@Composable
private fun SkillEditor(initial: Skill, exists: Boolean, onDismiss: () -> Unit, onSave: (Skill) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var notes by remember { mutableStateOf(initial.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (exists) "Edit skill" else "New skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (cues, what to drill)") })
                if (exists) TextButton(onClick = onDelete) { Text("Delete skill", color = Palette.red) }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(initial.copy(name = name.trim(), notes = notes.trim())) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TreeEditor(initial: SkillTree, exists: Boolean, onDismiss: () -> Unit, onSave: (SkillTree) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var sport by remember { mutableStateOf(initial.sport) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (exists) "Edit tree" else "New skill tree") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sport (20+ min counts as a workout)", Modifier.weight(1f))
                    Switch(sport, { sport = it })
                }
                if (exists) TextButton(onClick = onDelete) { Text("Delete tree and its skills", color = Palette.red) }
            }
        },
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onSave(initial.copy(name = name.trim(), sport = sport)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
