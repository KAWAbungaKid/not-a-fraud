package com.maven.workout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maven.workout.Graph
import com.maven.workout.core.Actions
import com.maven.workout.core.Cycle
import com.maven.workout.core.DayTemplate
import com.maven.workout.core.Fmt
import com.maven.workout.core.Injuries
import com.maven.workout.core.Joint
import com.maven.workout.core.Measure
import com.maven.workout.core.Mode
import com.maven.workout.core.Prescriber
import com.maven.workout.core.Risk
import com.maven.workout.core.Rpe
import com.maven.workout.core.SetKind
import com.maven.workout.core.Strength
import com.maven.workout.core.Suggest
import com.maven.workout.core.Week
import com.maven.workout.core.WorkoutData
import java.time.format.DateTimeFormatter

fun riskColor(r: Risk): Color = when (r) {
    Risk.MET -> Palette.green
    Risk.ON_TRACK -> Palette.text
    Risk.TIGHT -> Palette.amber
    Risk.MUST_TODAY, Risk.MISSED -> Palette.red
}

@Composable
fun TodayScreen(d: WorkoutData, onOpen: (Route) -> Unit) {
    val today = Graph.today()
    val week = Week.status(d, today)
    val sug = Suggest.today(d, today)
    val p = d.program()
    var mode by remember { mutableStateOf(Mode.NORMAL) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var showInjury by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(Graph.cycleNotes) }
    val template: DayTemplate? = p?.days?.firstOrNull { it.id == chosen } ?: sug.pick?.template

    fun start(t: DayTemplate?) {
        Graph.update { Actions.start(it, t?.id, mode, today, Graph.now()) }
        onOpen(Route.Workout)
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 12.dp)) {
                Text(today.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), style = MaterialTheme.typography.headlineSmall)
                Dim("${p?.name ?: "No program"} · ${Cycle.label(d.cycle, p)}")
            }
        }
        if (notes.isNotEmpty()) item {
            Panel {
                for (n in notes) Text(n)
                TextButton(onClick = { notes = emptyList(); Graph.cycleNotes = emptyList() }) { Text("OK") }
            }
        }
        item { WeekPanel(d, week) }

        val active = d.active
        if (active != null) {
            item {
                Panel(highlight = true) {
                    Text("In progress: ${active.name}", style = MaterialTheme.typography.titleMedium)
                    Dim("${active.doneSets} sets done · started ${Fmt.secs(((Graph.now() - active.startedAt) / 1000).toInt())} ago")
                    Button(onClick = { onOpen(Route.Workout) }) { Text("Resume") }
                }
            }
            return@LazyColumn
        }

        if (week.trainedToday) item {
            Panel {
                Text("Trained today ✓", color = Palette.green, style = MaterialTheme.typography.titleMedium)
                Dim("That counts. Anything else today is a bonus.")
            }
        }

        if (template != null) item {
            Panel(highlight = chosen == null && !week.trainedToday) {
                SectionLabel(if (chosen == null) "Today's workout" else "Chosen workout")
                Text(template.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                if (chosen == null) sug.pick?.reason?.let { Dim(it) }
                else Suggest.warnFor(d, template, today)?.let { Text(it, color = Palette.amber) }
                ChipRow(Mode.entries, { it == mode }, { it.label }) { m ->
                    mode = m
                    if (m == Mode.INJURED && Injuries.activeJoints(d).isEmpty()) showInjury = true
                }
                when (mode) {
                    Mode.SHORT -> Dim("Short: first exercises that fit ${d.settings.shortMinutes} min, 2 working sets each, no top set.")
                    Mode.INJURED -> Dim("Injured: skips anything loading ${Injuries.activeJoints(d).joinToString { it.label.lowercase() }.ifEmpty { "—" }}. Weights logged today won't change your maxes.")
                    Mode.NORMAL -> {}
                }
                Preview(d, template, mode)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { start(template) }) { Text("Start ${template.name}") }
                    if (chosen != null) TextButton(onClick = { chosen = null }) { Text("Back to suggestion") }
                }
            }
        }

        if (p != null) item {
            Panel {
                SectionLabel("Or pick a different day")
                sug.nudge?.let { Text(it, color = Palette.amber) }
                for (t in p.days) {
                    val n = sug.balance[t.id] ?: 0
                    val core = p.days.indexOf(t) < p.coreDays
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name + if (core) "" else "  ·  extra")
                            Dim("$n× in 4 weeks · ${t.exerciseIds.size} exercises")
                        }
                        OutlinedButton(onClick = { chosen = t.id }, enabled = t.id != template?.id) { Text("Choose") }
                    }
                }
                TextButton(onClick = { start(null) }) { Text("Empty workout (add exercises as you go)") }
            }
        }

        item { InjuryPanel(d, onAdd = { showInjury = true }) }
        item { Spacer(Modifier.padding(8.dp)) }
    }

    if (showInjury) InjuryDialog(onDismiss = { showInjury = false }) { joints, note ->
        Graph.update { Actions.addInjury(it, joints, note, today) }
        showInjury = false
    }
}

@Composable
fun WeekPanel(d: WorkoutData, w: com.maven.workout.core.WeekStatus) {
    val today = w.today
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("This week", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("${w.done} / ${w.minimum} min · ${w.target} full", style = Mono)
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                val day = w.weekStart.plusDays(i.toLong())
                val done = day in w.doneDates
                val isToday = day == today
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(day.dayOfWeek.name.take(1), style = MaterialTheme.typography.labelSmall,
                        color = if (isToday) Palette.accent else MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(
                        Modifier.size(22.dp)
                            .background(if (done) Palette.green else Color.Transparent, CircleShape)
                            .border(1.5.dp, if (isToday) Palette.accent else MaterialTheme.colorScheme.outline, CircleShape),
                    )
                }
            }
        }
        Text(w.message(), color = riskColor(w.risk))
        val streak = Week.streak(d, today)
        if (streak > 0) Dim("$streak week${if (streak == 1) "" else "s"} in a row at your minimum.")
    }
}

@Composable
private fun Preview(d: WorkoutData, t: DayTemplate, mode: Mode) {
    val today = Graph.today()
    val s = remember(d, t.id, mode) { Prescriber.session(d, t, mode, today, 0L, "preview") }
    val p = d.program()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (e in s.entries) {
            val ex = d.exercise(e.exerciseId) ?: continue
            val step = Prescriber.stepFor(d, ex, p, s.week, s.block)
            val first = e.sets.firstOrNull { it.kind == SetKind.WORK || it.kind == SetKind.TEST }
            val top = e.sets.firstOrNull { it.kind == SetKind.TOP }
            val load = when {
                e.skipped -> "skipped (${e.skipReason})"
                ex.measure == Measure.TIME -> first?.targetSeconds?.let { "beat ${Fmt.secs(it)}" } ?: "to failure"
                first?.kind == SetKind.TEST -> "test max (~${Rpe.fmt(first.targetWeight)})"
                else -> listOfNotNull(
                    top?.targetWeight?.let { "top ${Rpe.fmt(it)}×${top.targetReps}" },
                    first?.targetWeight?.let { "${Rpe.fmt(it)}" },
                ).joinToString(" · ").ifEmpty { "" }
            }
            Row {
                Text(ex.name, modifier = Modifier.weight(1f), color = if (e.skipped) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text(
                    if (e.skipped) load else "${Prescriber.describe(step, ex.measure).substringBefore(" ·")}  $load".trim(),
                    style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (s.entries.isEmpty()) Dim("No exercises in this day yet — add some under Program.")
    }
}

@Composable
fun InjuryPanel(d: WorkoutData, onAdd: () -> Unit) {
    val today = Graph.today()
    val active = d.injuries.filter { it.active }
    Panel {
        SectionLabel("Injuries")
        if (active.isEmpty()) Dim("None. Hurt something? Add it and use Injured mode — those exercises get skipped or lightened.")
        for (i in active) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(i.joints.joinToString { it.label })
                Dim("since ${i.since}${if (i.note.isNotBlank()) " · ${i.note}" else ""}")
            }
            TextButton(onClick = { Graph.update { Actions.healInjury(it, i.id, today) } }) { Text("Healed") }
        }
        OutlinedButton(onClick = onAdd) { Text("Add injury") }
    }
}

@Composable
fun InjuryDialog(onDismiss: () -> Unit, onSave: (Set<Joint>, String) -> Unit) {
    var joints by remember { mutableStateOf(setOf<Joint>()) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's hurt?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChipRow(Joint.entries, { it in joints }, { it.label }) { j -> joints = if (j in joints) joints - j else joints + j }
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(joints, note) }, enabled = joints.isNotEmpty()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
