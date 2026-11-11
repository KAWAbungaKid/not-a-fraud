package com.maven.workout.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maven.workout.Graph
import com.maven.workout.core.Actions
import com.maven.workout.core.Cycle
import com.maven.workout.core.DayTemplate
import com.maven.workout.core.Prescriber
import com.maven.workout.core.Program
import com.maven.workout.core.Progression
import com.maven.workout.core.Step
import com.maven.workout.core.StepFormat
import com.maven.workout.core.WorkoutData

@Composable
fun ProgramScreen(d: WorkoutData, onOpen: (Route) -> Unit) {
    val p = d.program()
    var confirmUse by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        if (p != null) item {
            Panel(highlight = true) {
                SectionLabel("Active program")
                Text(p.name, style = MaterialTheme.typography.titleLarge)
                if (p.notes.isNotBlank()) Dim(p.notes)
                Text(Cycle.label(d.cycle, p), style = Mono)
                Stepper("Week", d.cycle.week, 1..p.weeksPerBlock) { w -> Graph.update { Actions.setCycle(it, w, it.cycle.block) } }
                Stepper("Block", d.cycle.block, 1..p.blocks) { b -> Graph.update { Actions.setCycle(it, it.cycle.week, b) } }
                Dim("Moves on by itself: \"${d.settings.advance.label}\" (Settings). Cycles finished: ${d.cycle.cyclesDone}.")
                SectionLabel("Days (first ${p.coreDays} = your minimum split)")
                for ((i, t) in p.days.withIndex()) Row(
                    Modifier.fillMaxWidth().clickable { onOpen(Route.DayEdit(p.id, t.id)) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", style = Mono, modifier = Modifier.width(24.dp), color = if (i < p.coreDays) Palette.accent else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(t.name)
                        Dim(t.exerciseIds.mapNotNull { d.exercise(it)?.name }.joinToString(", "))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpen(Route.ProgramEdit(p.id)) }) { Text("Edit program") }
                    TextButton(onClick = { Graph.update { Actions.duplicateProgram(it, p.id) } }) { Text("Duplicate") }
                }
            }
        }
        item {
            Panel {
                SectionLabel("Presets")
                Dim("A preset is a whole program: days, exercises, cycle length. Duplicate one to make your own version.")
                for (pr in d.programs) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pr.name + if (pr.id == p?.id) "  ✓" else "")
                        Dim("${pr.days.size} days · ${pr.weeksPerBlock}×${pr.blocks} weeks")
                    }
                    if (pr.id != p?.id) TextButton(onClick = { confirmUse = pr.id }) { Text("Use") }
                    TextButton(onClick = { onOpen(Route.ProgramEdit(pr.id)) }) { Text("Edit") }
                }
                OutlinedButton(onClick = {
                    var id = ""
                    Graph.update {
                        id = Actions.slug("program", it.programs.map { x -> x.id }.toSet())
                        Actions.saveProgram(it, Program(id, "New program", days = listOf(DayTemplate(Actions.newId(), "Day 1"))))
                    }
                    onOpen(Route.ProgramEdit(id))
                }) { Text("+ New blank program") }
            }
        }
        item {
            Panel {
                SectionLabel("Types (progressions)")
                Dim("Each exercise picks a type. A type says sets × reps @ RPE for every week of the cycle; weights come from your e1RM.")
                for (pg in d.progressions) Row(
                    Modifier.fillMaxWidth().clickable { onOpen(Route.ProgressionEdit(pg.id)) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(pg.name)
                        val used = d.exercises.count { it.progressionId == pg.id && !it.archived }
                        Dim("${pg.steps.firstOrNull()?.let { StepFormat.format(it) } ?: "empty"} … ${pg.steps.lastOrNull()?.let { StepFormat.format(it) } ?: ""} · $used exercises")
                    }
                }
                OutlinedButton(onClick = {
                    var id = ""
                    Graph.update {
                        id = Actions.slug("type", it.progressions.map { x -> x.id }.toSet())
                        Actions.saveProgression(it, Progression(id, "New type", List(it.program()?.cycleWeeks ?: 12) { Step(3, 10, rpe = 8.0) }))
                    }
                    onOpen(Route.ProgressionEdit(id))
                }) { Text("+ New type") }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    confirmUse?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmUse = null },
            title = { Text("Switch to ${d.program(id)?.name}?") },
            text = { Text("The cycle restarts at week 1, block 1. Your history and maxes stay.") },
            confirmButton = { TextButton(onClick = { Graph.update { Actions.activate(it, id) }; confirmUse = null }) { Text("Switch") } },
            dismissButton = { TextButton(onClick = { confirmUse = null }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ProgramEditScreen(d: WorkoutData, id: String, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    val p = d.programs.firstOrNull { it.id == id } ?: run { Text("Deleted", Modifier.padding(16.dp)); return }
    fun save(f: (Program) -> Program) = Graph.update { d2 -> d2.programs.firstOrNull { it.id == id }?.let { Actions.saveProgram(d2, f(it)) } ?: d2 }
    var name by remember(id) { mutableStateOf(p.name) }
    var notes by remember(id) { mutableStateOf(p.notes) }
    var confirmDelete by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel {
                OutlinedTextField(name, { name = it; save { x -> x.copy(name = it) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it; save { x -> x.copy(notes = it) } }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
                Stepper("Weeks per block", p.weeksPerBlock, 1..8) { v -> save { it.copy(weeksPerBlock = v) } }
                Stepper("Blocks", p.blocks, 1..6) { v -> save { it.copy(blocks = v) } }
                Stepper("Core days (the minimum split)", p.coreDays, 1..p.days.size.coerceAtLeast(1)) { v -> save { it.copy(coreDays = v) } }
                Dim("Cycle = ${p.cycleWeeks} weeks. Types shorter than that repeat their last week.")
            }
        }
        item { SectionLabel("Days, in the order they're suggested") }
        itemsIndexed(p.days, key = { _, t -> t.id }) { i, t ->
            Panel(Modifier.clickable { onOpen(Route.DayEdit(p.id, t.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${i + 1}. ${t.name}" + if (i < p.coreDays) "" else "  · extra")
                        Dim("${t.exerciseIds.size} exercises")
                    }
                    IconButton(onClick = { save { it.copy(days = swap(it.days, i, i - 1)) } }) { Icon(Icons.Filled.ArrowUpward, "Up") }
                    IconButton(onClick = { save { it.copy(days = swap(it.days, i, i + 1)) } }) { Icon(Icons.Filled.ArrowDownward, "Down") }
                    IconButton(onClick = { save { it.copy(days = it.days.filter { x -> x.id != t.id }, coreDays = it.coreDays.coerceAtMost((it.days.size - 1).coerceAtLeast(1))) } }) { Icon(Icons.Filled.Close, "Remove day") }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val nid = Actions.newId()
                    save { it.copy(days = it.days + DayTemplate(nid, "Day ${it.days.size + 1}")) }
                    onOpen(Route.DayEdit(p.id, nid))
                }) { Text("+ Add day") }
                if (d.programs.size > 1) TextButton(onClick = { confirmDelete = true }) { Text("Delete program", color = Palette.red) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${p.name}?") },
        text = { Text("Workouts already logged stay in History.") },
        confirmButton = { TextButton(onClick = { Graph.update { Actions.deleteProgram(it, id) }; confirmDelete = false; onBack() }) { Text("Delete", color = Palette.red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

fun <T> swap(list: List<T>, i: Int, j: Int): List<T> =
    if (i !in list.indices || j !in list.indices) list else list.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }

@Composable
fun DayEditScreen(d: WorkoutData, programId: String, dayId: String, onBack: () -> Unit) {
    val p = d.programs.firstOrNull { it.id == programId }
    val t = p?.days?.firstOrNull { it.id == dayId } ?: run { Text("Deleted", Modifier.padding(16.dp)); return }
    fun save(f: (DayTemplate) -> DayTemplate) = Graph.update { d2 ->
        val pp = d2.programs.firstOrNull { it.id == programId } ?: return@update d2
        Actions.saveProgram(d2, pp.copy(days = pp.days.map { if (it.id == dayId) f(it) else it }))
    }
    var name by remember(dayId) { mutableStateOf(t.name) }
    var picker by remember { mutableStateOf(false) }
    var orFor by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(name, { name = it; save { x -> x.copy(name = it) } }, label = { Text("Day name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        itemsIndexed(t.exerciseIds) { i, exId ->
            val ex = d.exercise(exId)
            val step = ex?.let { Prescriber.stepFor(d, it, p, d.cycle.week, d.cycle.block) }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ex?.name ?: exId)
                        Dim((d.progression(ex?.progressionId)?.name ?: "no type") + " · this week: " + Prescriber.describe(step, ex?.measure ?: com.maven.workout.core.Measure.WEIGHT_REPS))
                    }
                    IconButton(onClick = { save { it.copy(exerciseIds = swap(it.exerciseIds, i, i - 1)) } }) { Icon(Icons.Filled.ArrowUpward, "Up") }
                    IconButton(onClick = { save { it.copy(exerciseIds = swap(it.exerciseIds, i, i + 1)) } }) { Icon(Icons.Filled.ArrowDownward, "Down") }
                    IconButton(onClick = { save { it.copy(exerciseIds = it.exerciseIds.filterIndexed { j, _ -> j != i }, alternatives = it.alternatives - exId) } }) { Icon(Icons.Filled.Close, "Remove") }
                }
                // Either / or: other exercises that can fill this slot; the workout lets you pick.
                val alts = t.alternatives[exId].orEmpty()
                for (a in alts) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("or " + (d.exercise(a)?.name ?: a), Modifier.weight(1f), color = Palette.accent)
                    IconButton(onClick = { Graph.update { Actions.setAlternatives(it, programId, dayId, exId, alts - a) } }) { Icon(Icons.Filled.Close, "Remove option") }
                }
                TextButton(onClick = { orFor = exId }) { Text(if (alts.isEmpty()) "+ or another exercise" else "+ another option") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { picker = true }) { Text("+ Add exercise") }
                Dim("The first exercise is kept even in Short mode — put the main lift first.")
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (picker) ExercisePicker(d, onDismiss = { picker = false }) { exId ->
        save { it.copy(exerciseIds = it.exerciseIds + exId) }
        picker = false
    }
    orFor?.let { slot ->
        ExercisePicker(d, onDismiss = { orFor = null }) { exId ->
            Graph.update { Actions.setAlternatives(it, programId, dayId, slot, t.alternatives[slot].orEmpty() + exId) }
            orFor = null
        }
    }
}

@Composable
fun ProgressionEditScreen(d: WorkoutData, id: String, onBack: () -> Unit) {
    val pg = d.progression(id) ?: run { Text("Deleted", Modifier.padding(16.dp)); return }
    val prog = d.program()
    val weeks = prog?.weeksPerBlock ?: 4
    val blocks = prog?.blocks ?: 3
    val total = weeks * blocks
    fun save(f: (Progression) -> Progression) = Graph.update { d2 -> d2.progression(id)?.let { Actions.saveProgression(d2, f(it)) } ?: d2 }
    fun setStep(i: Int, s: Step) = save { x ->
        val filled = List(maxOf(total, x.steps.size)) { k -> x.step(k) ?: Step() }
        x.copy(steps = filled.mapIndexed { k, old -> if (k == i) s else old })
    }
    var name by remember(id) { mutableStateOf(pg.name) }
    var notes by remember(id) { mutableStateOf(pg.notes) }
    var confirmDelete by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel {
                OutlinedTextField(name, { name = it; save { x -> x.copy(name = it) } }, label = { Text("Type name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it; save { x -> x.copy(notes = it) } }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
                Dim("Each cell: sets x reps @RPE. Add \"top3\" for a top set of 3, \"test\" for a max test. \"3xF\" = to failure, \"3x45s\" = timed. Weight is worked out from your e1RM.")
                val users = d.exercises.filter { it.progressionId == id && !it.archived }.joinToString { it.name }
                if (users.isNotEmpty()) Dim("Used by: $users")
            }
        }
        for (b in 1..blocks) {
            item {
                Panel {
                    SectionLabel("Block $b")
                    for (w in 1..weeks) {
                        val i = (b - 1) * weeks + (w - 1)
                        key("$id/$i") { StepCell("Week $w", pg.step(i), current = prog != null && d.cycle.week == w && d.cycle.block == b) { setStep(i, it) } }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (blocks > 1) OutlinedButton(onClick = {
                    save { x -> x.copy(steps = List(total) { k -> x.step(k % weeks) ?: Step() }) }
                }) { Text("Copy block 1 into every block") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete type", color = Palette.red) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${pg.name}?") },
        text = { Text("Exercises using it become \"just log it\".") },
        confirmButton = { TextButton(onClick = { Graph.update { Actions.deleteProgression(it, id) }; confirmDelete = false; onBack() }) { Text("Delete", color = Palette.red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun StepCell(label: String, step: Step?, current: Boolean, onValid: (Step) -> Unit) {
    var text by remember { mutableStateOf(step?.let { StepFormat.format(it) } ?: "") }
    LaunchedEffect(step) { if (step != null && StepFormat.parse(text) != step) text = StepFormat.format(step) }
    val ok = StepFormat.parse(text) != null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label + if (current) " ◀" else "", modifier = Modifier.width(76.dp), color = if (current) Palette.accent else MaterialTheme.colorScheme.onSurface)
        OutlinedTextField(
            text, { t -> text = t; StepFormat.parse(t)?.let(onValid) },
            singleLine = true, isError = !ok, textStyle = TextStyle(fontFamily = Mono.fontFamily, fontSize = 15.sp),
            modifier = Modifier.weight(1f),
        )
    }
}
