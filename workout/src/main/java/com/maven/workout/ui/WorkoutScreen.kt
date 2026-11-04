package com.maven.workout.ui

import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maven.workout.Graph
import com.maven.workout.core.Actions
import com.maven.workout.core.Entry
import com.maven.workout.core.Exercise
import com.maven.workout.core.Fmt
import com.maven.workout.core.Measure
import com.maven.workout.core.Mode
import com.maven.workout.core.Prescriber
import com.maven.workout.core.Rpe
import com.maven.workout.core.Session
import com.maven.workout.core.SetKind
import com.maven.workout.core.SetLog
import com.maven.workout.core.Strength
import com.maven.workout.core.WorkoutData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WorkoutScreen(d: WorkoutData, onClose: () -> Unit, onOpen: (Route) -> Unit) {
    val s = d.active
    if (s == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val ctx = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val now by produceState(Graph.now()) { while (true) { value = Graph.now(); delay(250) } }
    var restEnds by remember { mutableLongStateOf(0L) }
    var restBuzzed by remember { mutableStateOf(true) }
    var picker by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var editStart by remember { mutableStateOf(false) }
    var fixFinish by remember { mutableStateOf(false) }

    LaunchedEffect(now >= restEnds, restBuzzed) {
        if (!restBuzzed && now >= restEnds) {
            restBuzzed = true
            runCatching {
                ctx.getSystemService(VibratorManager::class.java).defaultVibrator
                    .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1))
            }
        }
    }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun startRest(ex: Exercise, set: SetLog) {
        val heavy = set.kind == SetKind.TOP || set.kind == SetKind.TEST || (set.targetReps ?: set.reps ?: 8) <= 5
        val sec = if (ex.measure == Measure.TIME) 90 else if (heavy) d.settings.restHeavySec else d.settings.restSec
        restEnds = Graph.now() + sec * 1000L
        restBuzzed = false
        // Also in the notification shade with a buzz at the end, for when the screen is off.
        com.maven.workout.notify.RestTimer.start(ctx, restEnds, "After ${ex.name}")
    }
    /** In a superset, rest comes after the last exercise of the group. */
    fun continuesSuperset(ei: Int): Boolean {
        val g = s.entries.getOrNull(ei)?.superset ?: return false
        return s.entries.getOrNull(ei + 1)?.superset == g
    }
    fun finished(saved: Session?) {
        com.maven.workout.notify.RestTimer.cancel(ctx)
        if (saved != null && d.settings.writeHealth) scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.maven.workout.link.HealthWrite.write(ctx, saved) } }
    }

    Column(Modifier.fillMaxSize()) {
        // Header
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleLarge)
                        if (s.reopenedEnd != null) Text("Editing the ${s.date} workout · Finish saves, Discard leaves it as it was", color = Palette.amber, style = MaterialTheme.typography.bodySmall)
                        Dim(
                            "Week ${s.week} · Block ${s.block} · ${Fmt.secs(((now - s.startedAt) / 1000).toInt())} ✎ · ${s.doneSets} set${if (s.doneSets == 1) "" else "s"}",
                            Modifier.clickable { editStart = true },
                        )
                    }
                    TextButton(onClick = onClose) { Text("Hide") }
                    Button(onClick = { confirmFinish = true }) { Text("Finish") }
                }
                ChipRow(Mode.entries, { it == s.mode }, { it.label }) { m ->
                    Graph.update { Actions.changeMode(it, m, Graph.today()) }
                }
                if (restEnds > now) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Rest ${Fmt.secs(((restEnds - now + 999) / 1000).toInt())}", style = Mono.copy(fontSize = 20.sp), color = Palette.accent, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { restEnds += 30_000; com.maven.workout.notify.RestTimer.start(ctx, restEnds, "Rest") }) { Text("+30s") }
                    TextButton(onClick = { restEnds = 0L; restBuzzed = true; com.maven.workout.notify.RestTimer.cancel(ctx) }) { Text("Skip") }
                }
            }
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Spacer(Modifier.height(4.dp)) }
            itemsIndexed(s.entries, key = { i, e -> "${s.id}/$i/${e.exerciseId}" }) { ei, e ->
                val ex = d.exercise(e.exerciseId)
                if (ex != null) EntryCard(d, s, ei, e, ex, now, onSetDone = { set -> if (continuesSuperset(ei)) { restEnds = 0L; com.maven.workout.notify.RestTimer.cancel(ctx) } else startRest(ex, set) }, onOpen = onOpen)
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Add exercise") }
                    var note by remember(s.id) { mutableStateOf(s.note) }
                    OutlinedTextField(note, { note = it; Graph.update { d2 -> Actions.setNote(d2, it) } },
                        label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { confirmFinish = true }) { Text("Finish workout") }
                        TextButton(onClick = { confirmDiscard = true }, colors = ButtonDefaults.textButtonColors(contentColor = Palette.red)) { Text("Discard") }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (picker) ExercisePicker(d, onDismiss = { picker = false }) { id ->
        Graph.update { Actions.addExercise(it, id, Graph.today()) }
        picker = false
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard this workout?") },
        text = { Text("Nothing from it is saved.") },
        confirmButton = { TextButton(onClick = { Graph.update { Actions.discard(it) }; com.maven.workout.notify.RestTimer.cancel(ctx); confirmDiscard = false; onClose() }) { Text("Discard", color = Palette.red) } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep going") } },
    )
    if (editStart) TimesDialog("When did you start?", s.startedAt, null, onDismiss = { editStart = false }) { start, _ ->
        Graph.update { Actions.setTimes(it, s.id, start, null, java.time.ZoneId.systemDefault()) }
        editStart = false
    }
    if (fixFinish) TimesDialog("Workout times", s.startedAt, Graph.now(), confirm = "Finish", onDismiss = { fixFinish = false }) { start, end ->
        var saved: Session? = null
        Graph.update {
            val moved = Actions.setTimes(it, s.id, start, null, java.time.ZoneId.systemDefault())
            val (nd, ss) = Actions.finish(moved, end ?: Graph.now()); saved = ss; nd
        }
        finished(saved)
        fixFinish = false
        onClose()
        saved?.let { onOpen(Route.SessionDetail(it.id)) }
    }
    if (confirmFinish) {
        val undone = s.entries.filter { !it.skipped }.sumOf { e -> e.sets.count { !it.done } }
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            title = { Text("Finish ${s.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (undone > 0) "$undone unticked sets will be dropped. Ticked sets are saved." else "Nice. Saving it.")
                    val hrs = (Graph.now() - s.startedAt) / 3_600_000.0
                    if (hrs >= 2.5) Text("It's been running ${"%.1f".format(hrs)} h — forgot to finish?", color = Palette.amber)
                    TextButton(onClick = { confirmFinish = false; fixFinish = true }) { Text("Change start / end time…") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    var saved: Session? = null
                    Graph.update { val (nd, ss) = Actions.finish(it, Graph.now()); saved = ss; nd }
                    finished(saved)
                    confirmFinish = false
                    onClose()
                    saved?.let { onOpen(Route.SessionDetail(it.id)) }
                }) { Text("Finish") }
            },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Not yet") } },
        )
    }
}

@Composable
private fun EntryCard(
    d: WorkoutData, s: Session, ei: Int, e: Entry, ex: Exercise, now: Long,
    onSetDone: (SetLog) -> Unit, onOpen: (Route) -> Unit,
) {
    val today = Graph.today()
    var menu by remember { mutableStateOf(false) }
    val step = Prescriber.stepFor(d, ex, d.program(), s.week, s.block)
    val tm = if (ex.measure == Measure.WEIGHT_REPS) Strength.trainingMax(d, ex.id, today) else null
    val last = remember(d.sessions.size, ex.id) { Strength.lastSets(d, ex.id) }
    val bestToday = Strength.best(s.copy(entries = listOf(e)), ex.id)

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                e.superset?.let { g -> Text("SUPERSET ${'A' + ((g - 1).mod(26))}", color = Palette.accent, style = MaterialTheme.typography.labelSmall) }
                Text(ex.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = if (e.skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Dim(listOfNotNull(
                    Prescriber.describe(step, ex.measure),
                    tm?.let { "e1RM ${Rpe.fmt(Rpe.round(it, 1.0))}" },
                    bestToday?.let { "today ${Rpe.fmt(Rpe.round(it.e1rm, 1.0))}" },
                ).joinToString(" · "))
                if (last.isNotEmpty()) Dim("Last: " + last.filter { it.kind != SetKind.WARMUP }.take(4).joinToString(", ") { setText(ex, it) })
            }
            Column {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Exercise menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; Graph.update { Actions.moveEntry(it, ei, -1) } })
                    DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; Graph.update { Actions.moveEntry(it, ei, 1) } })
                    DropdownMenuItem(text = { Text(if (e.skipped) "Do light sets" else "Skip today") }, onClick = { menu = false; Graph.update { Actions.toggleSkip(it, ei, today) } })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; Graph.update { Actions.removeEntry(it, ei) } })
                    val inGroup = e.superset != null && s.entries.getOrNull(ei + 1)?.superset == e.superset
                    if (ei < s.entries.lastIndex || inGroup) DropdownMenuItem(text = { Text(if (inGroup) "Unlink from the next exercise" else "Superset with the next exercise") }, onClick = { menu = false; Graph.update { Actions.toggleSuperset(it, ei) } })
                    if (ex.measure == Measure.WEIGHT_REPS && e.sets.none { it.kind == SetKind.WARMUP }) DropdownMenuItem(text = { Text("Add warm-up sets") }, onClick = { menu = false; Graph.update { Actions.addWarmups(it, ei) } })
                    DropdownMenuItem(text = { Text("Exercise details") }, onClick = { menu = false; onOpen(Route.ExerciseEdit(ex.id)) })
                }
            }
        }
        if (e.skipped) {
            Text("Skipped${if (e.skipReason.isNotBlank()) " — ${e.skipReason}" else ""}", color = Palette.amber)
            OutlinedButton(onClick = { Graph.update { Actions.toggleSkip(it, ei, today) } }) { Text("Do light sets instead (RPE 6)") }
            return@Panel
        }
        HeaderRow(ex)
        // Plate calculator for the heaviest set planned (top set or first working set).
        if (ex.measure == Measure.WEIGHT_REPS) e.sets.filter { it.kind != SetKind.WARMUP }.mapNotNull { it.weight ?: it.targetWeight }.maxOrNull()?.let { w ->
            if (w > d.settings.barWeight) Dim("Plates for ${Rpe.fmt(w)}: " + com.maven.workout.core.Plates.text(com.maven.workout.core.Plates.perSide(w, d.settings.barWeight, d.settings.plates)))
        }
        var workNo = 0
        for ((si, set) in e.sets.withIndex()) {
            val label = when (set.kind) {
                SetKind.WORK -> "${++workNo}"
                else -> set.kind.short
            }
            key(si) { SetRow(d, s, ei, si, set, ex, label, now, onSetDone) }
        }
        TextButton(onClick = { Graph.update { Actions.addSet(it, ei) } }) { Text("+ Add set") }
    }
}

fun setText(ex: Exercise, s: SetLog): String = when (ex.measure) {
    Measure.TIME -> Fmt.secs(s.seconds ?: 0) + (s.weight?.takeIf { it > 0 }?.let { " +${Rpe.fmt(it)}" } ?: "")
    Measure.REPS -> "${s.reps ?: 0}" + (s.weight?.takeIf { it > 0 }?.let { " +${Rpe.fmt(it)}" } ?: "")
    Measure.WEIGHT_REPS -> "${Rpe.fmt(s.weight)}×${s.reps ?: 0}" + (s.rpe?.let { "@${Rpe.fmt(it)}" } ?: "")
}

@Composable
private fun HeaderRow(ex: Exercise) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        h("SET", 34)
        when (ex.measure) {
            Measure.WEIGHT_REPS -> { h(Graph.data.settings.unit.uppercase(), 76); h("REPS", 54); h("RPE", 50) }
            Measure.REPS -> { h("+${Graph.data.settings.unit.uppercase()}", 64); h("REPS", 64) }
            Measure.TIME -> { h("TIME", 120); h("", 64) }
        }
    }
}

@Composable
private fun h(t: String, w: Int) = Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(w.dp), textAlign = TextAlign.Center)

@Composable
private fun SetRow(
    d: WorkoutData, s: Session, ei: Int, si: Int, set: SetLog, ex: Exercise, label: String, now: Long,
    onSetDone: (SetLog) -> Unit,
) {
    fun edit(f: (SetLog) -> SetLog) = Graph.update { Actions.editSet(it, ei, si, f) }
    fun tick() {
        val wasDone = set.done
        Graph.update { Actions.completeSet(it, ei, si, Graph.now()) }
        if (!wasDone) onSetDone(set)
    }
    val labelColor = when (set.kind) {
        SetKind.TOP -> Palette.accent
        SetKind.TEST -> Palette.red
        SetKind.WARMUP -> MaterialTheme.colorScheme.onSurfaceVariant
        SetKind.WORK -> MaterialTheme.colorScheme.onSurface
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Mono, color = labelColor, modifier = Modifier.width(34.dp), textAlign = TextAlign.Center)
        when (ex.measure) {
            Measure.WEIGHT_REPS -> {
                NumBox(set.weight, Rpe.fmt(set.targetWeight), { v -> edit { it.copy(weight = v) } }, 76.dp, done = set.done)
                NumBox(set.reps?.toDouble(), set.targetReps?.toString() ?: "", { v -> edit { it.copy(reps = v?.toInt()) } }, 54.dp, decimals = false, done = set.done)
                NumBox(set.rpe, Rpe.fmt(set.targetRpe), { v -> edit { it.copy(rpe = v?.coerceIn(1.0, 10.0)) } }, 50.dp, done = set.done)
            }
            Measure.REPS -> {
                NumBox(set.weight, "0", { v -> edit { it.copy(weight = v) } }, 64.dp, done = set.done)
                NumBox(set.reps?.toDouble(), set.targetReps?.let { "$it" } ?: "max", { v -> edit { it.copy(reps = v?.toInt()) } }, 64.dp, decimals = false, done = set.done)
            }
            Measure.TIME -> {
                val key = "${s.id}/$ei/$si"
                val started = Graph.stopwatches[key]
                if (started != null) {
                    Text(Fmt.secs(((now - started) / 1000).toInt()), style = Mono.copy(fontSize = 22.sp), color = Palette.accent,
                        modifier = Modifier.width(120.dp), textAlign = TextAlign.Center)
                    Button(onClick = {
                        val secs = ((Graph.now() - started) / 1000).toInt()
                        Graph.stopwatches.remove(key)
                        edit { it.copy(seconds = secs) }
                        tick()
                    }, colors = ButtonDefaults.buttonColors(containerColor = Palette.red)) { Text("Stop") }
                    return@Row
                }
                NumBox(set.seconds?.toDouble(), set.targetSeconds?.let { "beat ${Fmt.secs(it)}" } ?: "secs",
                    { v -> edit { it.copy(seconds = v?.toInt()) } }, 120.dp, decimals = false, done = set.done)
                if (!set.done) OutlinedButton(onClick = { Graph.stopwatches[key] = Graph.now() }) { Text("Go") }
                else Spacer(Modifier.width(64.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        FilledIconButton(
            onClick = { tick() },
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (set.done) Palette.green else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (set.done) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
            ),
        ) { Icon(Icons.Filled.Check, "Done") }
        if (!set.done) IconButton(onClick = { Graph.update { Actions.removeSet(it, ei, si) } }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Close, "Remove set", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Search the library; typing a new name offers to create it. */
@Composable
fun ExercisePicker(d: WorkoutData, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = d.exercises.filter { !it.archived && (q.isBlank() || it.name.contains(q.trim(), ignoreCase = true)) }.sortedBy { it.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, label = { Text("Search or new name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.height(320.dp)) {
                    if (q.isNotBlank() && d.exercises.none { it.name.equals(q.trim(), ignoreCase = true) }) item {
                        TextButton(onClick = {
                            var id = ""
                            Graph.update { d2 -> val ex = Actions.newExercise(d2, q); id = ex.id; Actions.saveExercise(d2, ex) }
                            onPick(id)
                        }) { Text("+ New: \"${q.trim()}\" (edit its type later)") }
                    }
                    itemsIndexed(list, key = { _, e -> e.id }) { _, ex ->
                        TextButton(onClick = { onPick(ex.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(ex.name, color = MaterialTheme.colorScheme.onSurface)
                                Dim(listOfNotNull(d.progression(ex.progressionId)?.name, ex.muscles.joinToString { it.label }.ifEmpty { null }).joinToString(" · "))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
