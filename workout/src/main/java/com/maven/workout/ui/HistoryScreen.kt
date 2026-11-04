package com.maven.workout.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maven.workout.Graph
import com.maven.workout.core.Actions
import com.maven.workout.core.Mode
import com.maven.workout.core.Rpe
import com.maven.workout.core.Session
import com.maven.workout.core.SetKind
import com.maven.workout.core.Strength
import com.maven.workout.core.Week
import com.maven.workout.core.WorkoutData
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(d: WorkoutData, onOpen: (Route) -> Unit) {
    val sessions = remember(d.sessions) { d.finished().sortedByDescending { it.startedAt } }
    val byWeek = sessions.groupBy { Week.start(LocalDate.parse(it.date), d.settings.weekStartsMonday) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        if (sessions.isEmpty()) item { Panel { Text("No workouts yet."); Dim("Finished workouts show up here with their e1RMs and PRs.") } }
        for ((ws, list) in byWeek) {
            item {
                val n = list.map { it.date }.distinct().size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Week of ${ws.format(DateTimeFormatter.ofPattern("MMM d"))}", Modifier.weight(1f))
                    Text("$n/${d.settings.minPerWeek}", style = Mono, color = if (n >= d.settings.minPerWeek) Palette.green else Palette.amber)
                }
                // Hard sets per muscle that week: what's getting a lot, what's getting nothing.
                val vol = remember(d.sessions, ws) { com.maven.workout.core.Volume.week(d, ws) }
                if (vol.isNotEmpty()) Dim("Sets: " + vol.entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key.label} ${it.value}" })
            }
            items(list, key = { it.id }) { s -> SessionRow(d, s) { onOpen(Route.SessionDetail(s.id)) } }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SessionRow(d: WorkoutData, s: Session, onClick: () -> Unit) {
    val prs = remember(s.id, d.sessions.size) { Strength.prs(d, s) }
    Panel(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium)
                Dim(LocalDate.parse(s.date).format(DateTimeFormatter.ofPattern("EEE MMM d")) +
                    " · ${s.doneSets} set${if (s.doneSets == 1) "" else "s"} · ${minutes(s)} min" + if (s.mode != Mode.NORMAL) " · ${s.mode.label.lowercase()}" else "")
            }
            if (prs.isNotEmpty()) Text("${prs.size} PR", color = Palette.green, style = Mono)
        }
    }
}

fun clock(ms: Long): String = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
    .toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a"))

fun minutes(s: Session): Long = ((s.endedAt ?: s.startedAt) - s.startedAt) / 60_000L

@Composable
fun SessionDetailScreen(d: WorkoutData, id: String, onBack: () -> Unit, onEditSets: () -> Unit = {}) {
    val s = d.sessions.firstOrNull { it.id == id }
    if (s == null) { Text("Deleted.", Modifier.padding(16.dp)); return }
    var confirm by remember { mutableStateOf(false) }
    var editTimes by remember { mutableStateOf(false) }
    val prs = Strength.prs(d, s)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel(highlight = prs.isNotEmpty()) {
                Text(s.name, style = MaterialTheme.typography.headlineSmall)
                Dim("${s.date} · ${clock(s.startedAt)}–${clock(s.endedAt ?: s.startedAt)} · ${minutes(s)} min · Week ${s.week} Block ${s.block} · ${s.mode.label}")
                Row {
                    TextButton(onClick = { editTimes = true }) { Text("Edit date & times") }
                    TextButton(onClick = {
                        if (Graph.data.active == null) { Graph.update { com.maven.workout.core.Actions.reopen(it, s.id) }; onEditSets() }
                    }, enabled = d.active == null) { Text("Edit sets") }
                }
                if (s.source == "hevy") Dim("Imported from Hevy.")
                Text("${s.doneSets} set${if (s.doneSets == 1) "" else "s"} · volume ${Rpe.fmt(Rpe.round(Strength.volume(s), 1.0))} ${d.settings.unit}", style = Mono)
                if (prs.isNotEmpty()) {
                    SectionLabel("Personal records")
                    for (p in prs) Text("★ ${p.label}", color = Palette.green)
                }
                if (s.mode == Mode.INJURED) Dim("Injured session: counts toward your week, not toward your maxes.")
                if (s.note.isNotBlank()) Text(s.note)
            }
        }
        items(s.entries) { e ->
            val ex = d.exercise(e.exerciseId)
            Panel {
                Text(ex?.name ?: e.exerciseId, style = MaterialTheme.typography.titleMedium)
                val best = Strength.best(s, e.exerciseId)
                best?.let { Dim("Best set e1RM ${Rpe.fmt(Rpe.round(it.e1rm, 1.0))}") }
                var n = 0
                for (set in e.sets) {
                    val label = if (set.kind == SetKind.WORK) "${++n}" else set.kind.short
                    Row {
                        Text(label, style = Mono, modifier = Modifier.padding(end = 12.dp))
                        Text(if (ex != null) setText(ex, set) else "", style = Mono)
                    }
                }
            }
        }
        item {
            TextButton(onClick = { confirm = true }) { Text("Delete this workout", color = Palette.red) }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (editTimes) TimesDialog("Workout times", s.startedAt, s.endedAt ?: s.startedAt, onDismiss = { editTimes = false }) { start, end ->
        Graph.update { Actions.setTimes(it, id, start, end, java.time.ZoneId.systemDefault()) }
        editTimes = false
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete this workout?") },
        text = { Text("It stops counting toward your week and your maxes.") },
        confirmButton = { TextButton(onClick = { Graph.update { Actions.deleteSession(it, id) }; confirm = false; onBack() }) { Text("Delete", color = Palette.red) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
