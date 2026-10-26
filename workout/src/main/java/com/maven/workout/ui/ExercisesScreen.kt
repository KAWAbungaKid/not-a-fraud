package com.maven.workout.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.maven.workout.Graph
import com.maven.workout.core.Actions
import com.maven.workout.core.E1rmPoint
import com.maven.workout.core.Exercise
import com.maven.workout.core.Fmt
import com.maven.workout.core.Joint
import com.maven.workout.core.Measure
import com.maven.workout.core.Muscle
import com.maven.workout.core.Rpe
import com.maven.workout.core.SetText
import com.maven.workout.core.Strength
import com.maven.workout.core.WorkoutData

@Composable
fun ExercisesScreen(d: WorkoutData, onOpen: (Route) -> Unit) {
    var q by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    val today = Graph.today()
    val list = d.exercises
        .filter { (showArchived || !it.archived) && (q.isBlank() || it.name.contains(q.trim(), true)) }
        .sortedBy { it.name.lowercase() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, label = { Text("Search or new exercise name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        val name = q.trim().ifEmpty { "New exercise" }
                        var id = ""
                        Graph.update { val ex = Actions.newExercise(it, name); id = ex.id; Actions.saveExercise(it, ex) }
                        q = ""
                        onOpen(Route.ExerciseEdit(id))
                    }) { Text(if (q.isBlank()) "+ New exercise" else "+ New \"${q.trim()}\"") }
                    Spacer(Modifier.weight(1f))
                    Dim("Archived")
                    Switch(showArchived, { showArchived = it })
                }
            }
        }
        items(list, key = { it.id }) { ex ->
            val tm = if (ex.measure == Measure.WEIGHT_REPS) Strength.trainingMax(d, ex.id, today) else null
            val best = if (ex.measure != Measure.WEIGHT_REPS) Strength.bestEffort(d, ex.id) else null
            Panel(Modifier.clickable { onOpen(Route.ExerciseEdit(ex.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ex.name + if (ex.archived) " (archived)" else "", style = MaterialTheme.typography.titleMedium)
                        Dim(listOfNotNull(d.progression(ex.progressionId)?.name ?: "no type", ex.muscles.joinToString { it.label }.ifEmpty { null }).joinToString(" · "))
                    }
                    Text(
                        when {
                            tm != null -> Rpe.fmt(Rpe.round(tm, 1.0))
                            best != null && ex.measure == Measure.TIME -> Fmt.secs(best)
                            best != null -> "$best reps"
                            else -> "—"
                        },
                        style = Mono, color = Palette.accent,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
fun ExerciseEditScreen(d: WorkoutData, id: String, onBack: () -> Unit) {
    val ex = d.exercise(id) ?: run { Text("Not found", Modifier.padding(16.dp)); return }
    val today = Graph.today()
    fun save(f: (Exercise) -> Exercise) = Graph.update { d2 -> d2.exercise(id)?.let { cur -> Actions.saveExercise(d2, f(cur)) } ?: d2 }
    var name by remember(id) { mutableStateOf(ex.name) }
    var typeMenu by remember { mutableStateOf(false) }
    var maxText by remember { mutableStateOf("") }
    val history = remember(d.sessions, d.maxes, id) { Strength.history(d, id) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel {
                OutlinedTextField(name, { name = it; if (it.isNotBlank()) save { e -> e.copy(name = it.trim()) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SectionLabel("Measured by")
                ChipRow(Measure.entries, { it == ex.measure }, { it.label }) { m -> save { it.copy(measure = m) } }
                SectionLabel("Type (how it's programmed)")
                Box {
                    OutlinedButton(onClick = { typeMenu = true }) { Text(d.progression(ex.progressionId)?.name ?: "None — just log it") }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        DropdownMenuItem(text = { Text("None — just log it") }, onClick = { typeMenu = false; save { it.copy(progressionId = null) } })
                        for (p in d.progressions) DropdownMenuItem(text = { Text(p.name) }, onClick = { typeMenu = false; save { it.copy(progressionId = p.id) } })
                    }
                }
                d.progression(ex.progressionId)?.notes?.takeIf { it.isNotBlank() }?.let { Dim(it) }
                if (ex.measure != Measure.TIME) {
                    SectionLabel("Round weights to")
                    ChipRow(listOf(1.0, 2.5, 5.0, 10.0), { it == ex.roundTo }, { Rpe.fmt(it) }) { r -> save { it.copy(roundTo = r) } }
                }
                SectionLabel("Muscles")
                ChipRow(Muscle.entries, { it in ex.muscles }, { it.label }) { m -> save { it.copy(muscles = if (m in it.muscles) it.muscles - m else it.muscles + m) } }
                SectionLabel("Joints it loads (injured mode skips these)")
                ChipRow(Joint.entries, { it in ex.joints }, { it.label }) { j -> save { it.copy(joints = if (j in it.joints) it.joints - j else it.joints + j) } }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Archived (hidden from pickers)", Modifier.weight(1f))
                    Switch(ex.archived, { a -> save { it.copy(archived = a) } })
                }
            }
        }
        if (ex.measure == Measure.WEIGHT_REPS) item {
            Panel {
                SectionLabel("Strength")
                val tm = Strength.trainingMax(d, id, today)
                Text(if (tm != null) "Training max (e1RM) ${Rpe.fmt(Rpe.round(tm, 1.0))} ${d.settings.unit}" else "No max yet", style = MaterialTheme.typography.titleMedium)
                Dim("Best e1RM in the last ${d.settings.maxWindowDays} days. Every workout adds a point; injured-mode workouts don't count.")
                if (history.size >= 2) E1rmChart(history)
                for (p in history.takeLast(6).reversed()) Row {
                    Text(p.date.toString(), style = Mono, modifier = Modifier.weight(1f))
                    Text("${Rpe.fmt(p.weight)}×${p.reps}${p.rpe?.let { "@${Rpe.fmt(it)}" } ?: ""} → ${Rpe.fmt(Rpe.round(p.e1rm, 1.0))}${if (p.sessionId == null) " (typed)" else ""}", style = Mono)
                }
                SectionLabel("Type in a max")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(maxText, { maxText = it }, label = { Text("e.g. 245x4 or 75x8@8.5") }, singleLine = true, modifier = Modifier.weight(1f))
                    val parsed = SetText.parse(maxText)
                    TextButton(enabled = parsed != null, onClick = {
                        val (w, r, rpe) = parsed!!
                        Graph.update { Actions.addMax(it, id, today, w, r, rpe) }
                        maxText = ""
                    }) { Text("Add") }
                }
                SetText.parse(maxText)?.let { (w, r, rpe) -> Rpe.e1rm(w, r, rpe)?.let { Dim("= e1RM ${Rpe.fmt(Rpe.round(it, 1.0))}") } }
            }
        } else item {
            Panel {
                SectionLabel("Best")
                val b = Strength.bestEffort(d, id)
                Text(if (b == null) "Nothing logged yet" else if (ex.measure == Measure.TIME) Fmt.secs(b) else "$b reps", style = MaterialTheme.typography.titleMedium)
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun E1rmChart(points: List<E1rmPoint>) {
    val accent = Palette.accent
    val grid = MaterialTheme.colorScheme.outline
    val minV = points.minOf { it.e1rm }
    val maxV = points.maxOf { it.e1rm }
    val t0 = points.first().date.toEpochDay().toFloat()
    val t1 = points.last().date.toEpochDay().toFloat().coerceAtLeast(t0 + 1)
    Row {
        Column(Modifier.height(120.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(Rpe.fmt(Rpe.round(maxV, 1.0)), style = MaterialTheme.typography.labelSmall)
            Text(Rpe.fmt(Rpe.round(minV, 1.0)), style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp).padding(start = 8.dp)) {
            val span = (maxV - minV).coerceAtLeast(1.0)
            fun at(p: E1rmPoint) = Offset(
                (p.date.toEpochDay() - t0) / (t1 - t0) * size.width,
                (size.height - ((p.e1rm - minV) / span * size.height)).toFloat(),
            )
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            val path = Path()
            points.forEachIndexed { i, p -> val o = at(p); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, accent, style = Stroke(width = 4f))
            for (p in points) drawCircle(if (p.injured) Palette.red else accent, 6f, at(p))
        }
    }
}
