package com.maven.notafraud.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.Anchor
import com.maven.notafraud.core.model.AnchorKind
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.ProjectKind
import com.maven.notafraud.core.model.Semester
import com.maven.notafraud.core.store.Actions
import java.time.LocalDate

private val DAY = listOf("", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private fun meetingText(a: Anchor) = a.days.sorted().joinToString("/") { DAY[it] } + " " + hm(a.startMin) + "–" + hm(a.endMin)

/** Semester dates, class times and study time; "End semester" clears it for the next one. */
@Composable
fun ClassesScreen(data: AppData, now: Long) {
    val ctx = LocalContext.current
    val sem = data.semester
    val classes = data.anchors.filter { it.kind == AnchorKind.CLASS }.groupBy { it.title }.toSortedMap()
    var editingSem by remember { mutableStateOf(false) }
    var editingClass by remember { mutableStateOf<String?>(null) }
    var ending by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Panel(Modifier.clickable { editingSem = true }, highlight = sem == null) {
                if (sem == null) {
                    Text("No semester set", style = MaterialTheme.typography.titleMedium)
                    Text("Tap to set the dates. Classes only show up in the plan between them.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(sem.name, style = MaterialTheme.typography.titleMedium)
                    Text("${sem.start} → ${sem.end}" + if (sem.offDates.isNotEmpty()) " · ${sem.offDates.size} days off" else "", color = Palette.textDim)
                    Text("Tap to edit", color = Palette.textDim, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item { SectionLabel("Classes") }
        items(classes.entries.toList(), key = { it.key }) { (name, meetings) ->
            val project = Actions.classProject(data, name)
            val study = data.tasks.firstOrNull { it.sourceKey == "catchup:${project?.id}" && it.status == com.maven.notafraud.core.model.TaskStatus.TODO }
            Panel(Modifier.clickable { editingClass = name }) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(meetings.joinToString(" · ") { meetingText(it) }, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                study?.weeklyTargetMin?.let { Text("Study ${com.maven.notafraud.core.plan.Time.durationText(it)}/week", color = Palette.textDim, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (classes.isEmpty()) item { Text("No classes yet.", color = Palette.textDim) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { editingClass = "" }) { Text("Add class") }
                if (sem != null || classes.isNotEmpty()) OutlinedButton(onClick = { ending = true }) { Text("End semester") }
            }
        }
        item {
            Text(
                "Exams and assignments come from your calendars (More → Calendar & health: Tests and Deadlines calendars), so they don't need entering here.",
                color = Palette.textDim, style = MaterialTheme.typography.bodySmall,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (editingSem) SemesterDialog(sem, onDismiss = { editingSem = false }) { s ->
        Graph.changeAndReplan(ctx) { Actions.setSemester(it, s) }; editingSem = false
    }
    editingClass?.let { name ->
        ClassEditor(data, name.ifBlank { null }, onDismiss = { editingClass = null }, onSave = { newName, meetings, studyMin ->
            Graph.changeAndReplan(ctx) { Actions.saveClass(it, newName, meetings, name.ifBlank { null }, studyMin, Graph.now()) }; editingClass = null
        }, onDelete = { Graph.changeAndReplan(ctx) { Actions.deleteClass(it, name) }; editingClass = null })
    }
    if (ending) {
        val school = data.projects.filter { !it.archived && it.kind == ProjectKind.SCHOOL }
        var chosen by remember { mutableStateOf(school.filter { p -> classes.keys.any { Actions.classProject(data, it)?.id == p.id } || classes.isEmpty() }.map { it.id }.toSet()) }
        AlertDialog(
            onDismissRequest = { ending = false },
            title = { Text("End the semester?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Class blocks are removed and the checked classes are archived: their tasks and logged time stay, nothing in them gets planned, weekly study for them stops. Restore any of them under More → Projects.",
                        color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                    for (p in school) Row(Modifier.fillMaxWidth().clickable { chosen = if (p.id in chosen) chosen - p.id else chosen + p.id }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(p.id in chosen, null)
                        Text(p.name)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { Graph.changeAndReplan(ctx) { Actions.endSemester(it, chosen, Graph.now()) }; ending = false }) { Text("End semester") } },
            dismissButton = { TextButton(onClick = { ending = false }) { Text("Cancel") } },
        )
    }
}

/** "2026-11-23" or "2026-11-23 to 2026-11-27", one per line. */
private fun parseDates(lines: List<String>): Set<String> = lines.flatMap { l ->
    val parts = l.split(" to ", "..", "–").map { it.trim() }
    val a = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return@flatMap emptyList()
    val b = parts.getOrNull(1)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: a
    generateSequence(a) { it.plusDays(1) }.takeWhile { !it.isAfter(b) }.take(120).map { it.toString() }.toList()
}.toSet()

@Composable
private fun SemesterDialog(initial: Semester?, onDismiss: () -> Unit, onSave: (Semester) -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var start by remember { mutableStateOf(initial?.start ?: "") }
    var end by remember { mutableStateOf(initial?.end ?: "") }
    var off by remember { mutableStateOf(initial?.offDates?.sorted()?.joinToString("\n") ?: "") }
    val ok = name.isNotBlank() && runCatching { LocalDate.parse(start.trim()) <= LocalDate.parse(end.trim()) }.getOrDefault(false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Semester") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (Spring 2027)") }, singleLine = true)
                OutlinedTextField(start, { start = it }, label = { Text("First day (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(end, { end = it }, label = { Text("Last day (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(off, { off = it }, label = { Text("Days off: a date or \"date to date\" per line") }, modifier = Modifier.height(140.dp))
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onSave(Semester(name.trim(), start.trim(), end.trim(), parseDates(off.lines()))) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ClassEditor(data: AppData, name: String?, onDismiss: () -> Unit, onSave: (String, List<Anchor>, Int) -> Unit, onDelete: () -> Unit) {
    var title by remember { mutableStateOf(name ?: "") }
    var meetings by remember {
        mutableStateOf(data.anchors.filter { it.kind == AnchorKind.CLASS && it.title == name }.ifEmpty {
            listOf(Anchor(title = "", kind = AnchorKind.CLASS, startMin = 9 * 60, endMin = 9 * 60 + 50, days = setOf(1, 3, 5)))
        })
    }
    val project = name?.let { Actions.classProject(data, it) }
    var study by remember { mutableStateOf(data.tasks.firstOrNull { it.sourceKey == "catchup:${project?.id}" && it.status == com.maven.notafraud.core.model.TaskStatus.TODO }?.weeklyTargetMin ?: if (name == null) 120 else 0) }
    var picking by remember { mutableStateOf<Pair<Int, String>?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (name == null) "New class" else "Edit class") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Name (ME 4000 Design)") }, singleLine = true)
                meetings.forEachIndexed { i, m ->
                    SectionLabel("Meets")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (d in 1..7) FilterChip(d in m.days, {
                            meetings = meetings.mapIndexed { j, x -> if (j == i) x.copy(days = if (d in x.days) x.days - d else x.days + d) else x }
                        }, { Text(DAY[d].take(2)) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { picking = i to "start" }) { Text("From ${hm(m.startMin)}") }
                        TextButton(onClick = { picking = i to "end" }) { Text("to ${hm(m.endMin)}") }
                        if (meetings.size > 1) TextButton(onClick = { meetings = meetings.filterIndexed { j, _ -> j != i } }) { Text("Remove", color = Palette.textDim) }
                    }
                }
                TextButton(onClick = { meetings = meetings + Anchor(title = "", kind = AnchorKind.CLASS, startMin = 14 * 60, endMin = 14 * 60 + 50, days = setOf(2)) }) {
                    Text("+ Another time (lab, recitation)")
                }
                SectionLabel("Study time per week (planned for you)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf(0, 60, 120, 180, 240, 360)) FilterChip(study == m, { study = m }, { Text(if (m == 0) "none" else com.maven.notafraud.core.plan.Time.durationText(m)) })
                }
                if (name != null) TextButton(onClick = onDelete) { Text("Remove class times", color = Palette.red) }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank() && meetings.all { it.days.isNotEmpty() && it.startMin < it.endMin }, onClick = { onSave(title.trim(), meetings, study) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    picking?.let { (i, which) ->
        val m = meetings[i]
        TimeDialog(if (which == "start") m.startMin else m.endMin, onDismiss = { picking = null }) { t ->
            meetings = meetings.mapIndexed { j, x ->
                if (j != i) x else if (which == "start") x.copy(startMin = t, endMin = maxOf(x.endMin, t + 50)) else x.copy(endMin = t)
            }
            picking = null
        }
    }
}
