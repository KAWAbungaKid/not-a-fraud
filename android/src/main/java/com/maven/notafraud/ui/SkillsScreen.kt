package com.maven.notafraud.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.Skill
import com.maven.notafraud.core.model.SkillTree
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.stats.Skills
import com.maven.notafraud.core.store.Actions
import kotlinx.coroutines.launch
import kotlin.random.Random

/*
 * Skyrim-style: each tree is a constellation in a night sky, swiped sideways. Skills are stars that grow
 * brighter with level; lines between them light up as the path opens. Roots sit at the bottom and the
 * tree grows upward. Tap a star for its details.
 */

private val SKY_TOP = Color(0xFF02040A)
private val SKY_BOTTOM = Color(0xFF0B1630)
private val STAR = Color(0xFFEAF2FF)
private val ROW_H = 120.dp

private data class StarPos(val x: Float, val row: Int, val jx: Float, val jy: Float)

/** Tidy layout (leaves take slots, parents centre over children), plus a small fixed wobble so it reads as a constellation. */
private fun layout(skills: List<Skill>): Pair<Map<String, StarPos>, Float> {
    val ids = skills.map { it.id }.toSet()
    val byParent = skills.groupBy { it.parentId?.takeIf { p -> p in ids } }
    val pos = HashMap<String, StarPos>()
    var next = 0f
    fun place(s: Skill, depth: Int): Float {
        val kids = byParent[s.id].orEmpty()
        val x = if (kids.isEmpty()) next.also { next += 1f } else kids.map { place(it, depth + 1) }.let { (it.first() + it.last()) / 2f }
        val r = Random(s.id.hashCode())
        pos[s.id] = StarPos(x, depth, r.nextFloat() * 0.36f - 0.18f, r.nextFloat() * 0.3f - 0.15f)
        return x
    }
    for (root in byParent[null].orEmpty()) place(root, 0)
    return pos to next.coerceAtLeast(1f)
}

@Composable
fun SkillsScreen(data: AppData) {
    val trees = data.skillTrees.sortedBy { it.order }
    var selected by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<Skill?>(null) }
    var editingTree by remember { mutableStateOf<SkillTree?>(null) }
    val pager = rememberPagerState { trees.size.coerceAtLeast(1) }
    val scope = rememberCoroutineScope()
    val tree = trees.getOrNull(pager.currentPage)

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(SKY_TOP, SKY_BOTTOM)))) {
        if (trees.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No constellations yet", color = STAR)
                TextButton(onClick = { editingTree = SkillTree(name = "") }) { Text("+ New skill tree") }
            }
        } else {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val t = trees[page]
                Constellation(data, t, data.skills.filter { it.treeId == t.id }, selected) { selected = if (selected == it) null else it }
            }
            // Top: tree actions.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { adding = Skill(treeId = tree!!.id, name = "", parentId = selected?.takeIf { s -> data.skills.any { it.id == s && it.treeId == tree.id } }) }) {
                    Text(if (selected != null) "+ Skill above this" else "+ Skill", color = STAR.copy(alpha = 0.85f))
                }
                TextButton(onClick = { editingTree = tree }) { Text("Edit tree", color = STAR.copy(alpha = 0.6f)) }
                TextButton(onClick = { editingTree = SkillTree(name = "", order = (trees.maxOfOrNull { it.order } ?: 0) + 1) }) { Text("+ Tree", color = STAR.copy(alpha = 0.6f)) }
            }
            // Bottom: the tree's name and level, Skyrim style, with page dots.
            val sel = selected?.let { id -> data.skills.firstOrNull { it.id == id && it.treeId == tree?.id } }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (sel != null) {
                    SkillPanel(data, sel, onAddChild = { adding = Skill(treeId = sel.treeId, name = "", parentId = sel.id) }, onClose = { selected = null })
                } else if (tree != null) {
                    val skills = data.skills.filter { it.treeId == tree.id }
                    val total = skills.sumOf { Skills.level(Skills.minutes(data, it.id)) }
                    val hours = skills.sumOf { Skills.minutes(data, it.id) }
                    Text(
                        tree.name.uppercase(), color = STAR,
                        style = TextStyle(fontSize = 22.sp, letterSpacing = 6.sp, fontWeight = FontWeight.Light),
                    )
                    Text("$total" + (if (tree.sport) "  ·  sport" else "") + "  ·  " + Time.durationText(hours), color = STAR.copy(alpha = 0.6f), style = TextStyle(fontSize = 13.sp, letterSpacing = 3.sp))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (i in trees.indices) Box(
                            Modifier.size(if (i == pager.currentPage) 8.dp else 6.dp).clip(CircleShape)
                                .background(STAR.copy(alpha = if (i == pager.currentPage) 0.9f else 0.3f))
                                .clickable { scope.launch { pager.animateScrollToPage(i) } },
                        )
                    }
                    Text("Swipe for other trees · tap a star", color = STAR.copy(alpha = 0.35f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }

    adding?.let { s -> SkillEditor(s, data.skills.any { it.id == s.id }, onDismiss = { adding = null }, onSave = { Graph.update { d -> Actions.upsertSkill(d, it) }; adding = null }, onDelete = {
        Graph.update { Actions.deleteSkill(it, s.id) }; adding = null; selected = null
    }) }
    editingTree?.let { t -> TreeEditor(t, data.skillTrees.any { it.id == t.id }, onDismiss = { editingTree = null }, onSave = { nt ->
        Graph.update { d -> Actions.upsertTree(d, nt) }; editingTree = null
        scope.launch { val i = (Graph.data.skillTrees.sortedBy { it.order }).indexOfFirst { it.id == nt.id }; if (i >= 0) pager.scrollToPage(i) }
    }, onDelete = { Graph.update { Actions.deleteTree(it, t.id) }; editingTree = null; selected = null }) }
}

@Composable
private fun Constellation(data: AppData, tree: SkillTree, skills: List<Skill>, selected: String?, onSelect: (String) -> Unit) {
    val (pos, cols) = remember(skills) { layout(skills) }
    val rows = (pos.values.maxOfOrNull { it.row } ?: 0) + 1
    val twinkle = 1f
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val bottomPad = 190.dp
        val skyH = maxOf(maxHeight, ROW_H * rows + bottomPad + 80.dp)
        val slot = w / (cols + 0.6f)
        fun px(p: StarPos): Dp = slot * (p.x + 0.8f + p.jx)
        fun py(p: StarPos): Dp = skyH - bottomPad - ROW_H * (p.row + p.jy) - 30.dp
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState(Int.MAX_VALUE))) {
            Box(Modifier.fillMaxWidth().height(skyH)) {
                Canvas(Modifier.fillMaxSize()) {
                    // Background stars, fixed per tree.
                    val r = Random(tree.id.hashCode())
                    repeat(170) {
                        val a = r.nextFloat()
                        drawCircle(STAR.copy(alpha = 0.08f + 0.35f * a * a), radius = (0.6f + r.nextFloat() * 1.3f) * density.density,
                            center = Offset(r.nextFloat() * size.width, r.nextFloat() * size.height))
                    }
                    // A faint nebula in the accent colour behind the constellation.
                    drawCircle(Brush.radialGradient(listOf(Palette.green.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width / 2, size.height - bottomPad.toPx() - ROW_H.toPx() * rows / 2), radius = size.width * 0.7f),
                        radius = size.width * 0.7f, center = Offset(size.width / 2, size.height - bottomPad.toPx() - ROW_H.toPx() * rows / 2))
                    // Lines.
                    for (s in skills) {
                        val p = s.parentId?.let { pos[it] } ?: continue
                        val c = pos[s.id] ?: continue
                        val lit = Skills.unlocked(data, s.id)
                        drawLine(
                            STAR.copy(alpha = if (lit) 0.55f else 0.14f),
                            Offset(px(p).toPx(), py(p).toPx()), Offset(px(c).toPx(), py(c).toPx()),
                            strokeWidth = (if (lit) 1.6f else 1f) * density.density,
                        )
                    }
                    // Stars: brighter and bigger with level; locked ones are dim specks.
                    for (s in skills) {
                        val p = pos[s.id] ?: continue
                        val lvl = Skills.level(Skills.minutes(data, s.id))
                        val open = Skills.unlocked(data, s.id)
                        val center = Offset(px(p).toPx(), py(p).toPx())
                        val core = (if (!open) 2.2f else 3.2f + lvl * 0.9f) * density.density
                        val glow = if (!open) 0f else (10f + lvl * 7f) * density.density * twinkle
                        if (glow > 0f) drawCircle(
                            Brush.radialGradient(listOf(STAR.copy(alpha = if (lvl > 0) 0.55f else 0.22f), Color.Transparent), center, glow), glow, center,
                        )
                        if (s.id == selected) drawCircle(Palette.green, core + 7f * density.density, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f * density.density))
                        drawCircle(if (open) STAR else STAR.copy(alpha = 0.35f), core, center)
                    }
                }
                // Tap targets and names.
                for (s in skills) {
                    val p = pos[s.id] ?: continue
                    val lvl = Skills.level(Skills.minutes(data, s.id))
                    val open = Skills.unlocked(data, s.id)
                    Box(
                        Modifier.offset(px(p) - 28.dp, py(p) - 28.dp).size(56.dp).clip(CircleShape)
                            .clickable(remember { MutableInteractionSource() }, null) { onSelect(s.id) },
                    )
                    Column(Modifier.offset(px(p) - 55.dp, py(p) + 14.dp).width(110.dp).clickable(remember { MutableInteractionSource() }, null) { onSelect(s.id) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            s.name.uppercase(), color = STAR.copy(alpha = if (open) 0.85f else 0.35f), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            style = TextStyle(fontSize = 10.sp, letterSpacing = 1.5.sp),
                        )
                        if (open && lvl > 0) Text("$lvl", color = Palette.green.copy(alpha = 0.9f), style = TextStyle(fontSize = 10.sp))
                    }
                }
                if (skills.isEmpty()) Text("Empty sky. Tap + Skill.", color = STAR.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

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
