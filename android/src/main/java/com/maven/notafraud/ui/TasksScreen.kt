@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maven.notafraud.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.Energy
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskStatus
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.score.Scorer
import com.maven.notafraud.core.store.Actions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TasksScreen(data: AppData, now: Long) {
    val ctx = LocalContext.current
    val zone = Time.zone(data.settings.zoneId)
    var tab by rememberSaveable { mutableStateOf(if (Graph.openTodo) 2 else 0) }
    Graph.openTodo = false
    var addingProject by remember { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Task?>(null) }
    val q = query.trim().lowercase()
    val projectName = data.projects.associate { it.id to it.name }
    val tasks = data.tasks.filter { (it.status == TaskStatus.DONE) == showDone }
        .filter { q.isEmpty() || it.title.lowercase().contains(q) || projectName[it.projectId]?.lowercase()?.contains(q) == true || it.notes.lowercase().contains(q) }
    val byProject = tasks.groupBy { it.projectId }
    val projectOrder = data.projects.map { it.id } + listOf(null)

    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            listOf("Map", "Projects", "To-do").forEachIndexed { i, label ->
                SegmentedButton(
                    selected = tab == i, onClick = { tab = i },
                    shape = androidx.compose.material3.SegmentedButtonDefaults.itemShape(i, 3),
                ) { Text(label) }
            }
        }
        if (tab == 0) {
            MapView(data, now, onEditTask = { editing = it }, onNewTask = { pid -> editing = Task(title = "", projectId = pid, createdAt = Graph.now()) })
        } else if (tab == 2) {
            TodoScreen(data, now)
        } else Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    androidx.compose.material3.Button(onClick = { editing = Task(title = "", createdAt = Graph.now()) }) { Text("+ Task") }
                    androidx.compose.material3.OutlinedButton(onClick = { addingProject = true }) { Text("+ Project") }
                }
                OutlinedTextField(
                    query, { query = it }, singleLine = true, placeholder = { Text("Search tasks and projects") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showDone, onClick = { showDone = false }, label = { Text("Open (${data.tasks.count { it.status == TaskStatus.TODO }})") })
                    FilterChip(selected = showDone, onClick = { showDone = true }, label = { Text("Done") })
                }
            }

            for (pid in projectOrder) {
                val list = byProject[pid] ?: continue
                val name = projectName[pid] ?: "No project"
                item(key = "h-$pid") { SectionLabel(name) }
                items(list.sortedWith(compareBy<Task> { it.dueAt ?: Long.MAX_VALUE }.thenBy { it.title }), key = { it.id }) { t ->
                    TaskRow(t, now, zone, onToggle = { done -> Graph.changeAndReplan(ctx) { Actions.setDone(it, t.id, done, Graph.now()) } }, onClick = { editing = t })
                }
            }
            if (tasks.isEmpty()) item { Text(if (q.isNotEmpty()) "Nothing matches." else if (showDone) "Nothing done yet." else "No open tasks.", color = Palette.textDim) }
            item { Spacer(Modifier.height(88.dp)) }
        }
        }
    }

    if (addingProject) ProjectDialog(data, onDismiss = { addingProject = false }) { name, areaId ->
        Graph.changeAndReplan(ctx) { Actions.upsertProject(it, com.maven.notafraud.core.model.Project(name = name, kind = com.maven.notafraud.core.model.ProjectKind.PROJECT, areaId = areaId)) }
        addingProject = false
    }

    editing?.let { t ->
        TaskEditor(
            initial = t, data = data, isNew = data.tasks.none { it.id == t.id },
            onDismiss = { editing = null },
            onSave = { saved ->
                Graph.changeAndReplan(ctx) { d -> if (d.tasks.any { it.id == saved.id }) Actions.updateTask(d, saved) else Actions.addTask(d, saved) }
                editing = null
            },
            onDelete = { Graph.changeAndReplan(ctx) { Actions.deleteTask(it, t.id) }; editing = null },
        )
    }
}

@Composable
private fun TaskRow(t: Task, now: Long, zone: java.time.ZoneId, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Panel(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = t.status == TaskStatus.DONE, onCheckedChange = onToggle)
            Column(Modifier.weight(1f)) {
                Text(
                    t.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (t.status == TaskStatus.DONE) TextDecoration.LineThrough else null,
                )
                val meta = listOfNotNull(
                    "${t.loggedMin}/${t.estMin}m",
                    t.dueAt?.let { "due " + dueText(it, now, zone) },
                    if (t.priority == Priority.HIGH && t.dueAt != null) "firm deadline" else null,
                    t.importance?.let { com.maven.notafraud.core.plan.Importance.name(it) }?.takeIf { it != "Normal" },
                    t.notBefore?.takeIf { it > now }?.let { "from " + Time.dateOf(it, zone).format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)) },
                )
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
            }
        }
    }
}

@Composable
fun TaskEditor(initial: Task, data: AppData, isNew: Boolean, onDismiss: () -> Unit, onSave: (Task) -> Unit, onDelete: () -> Unit) {
    val zone = Time.zone(data.settings.zoneId)
    var t by remember { mutableStateOf(initial) }
    var est by remember { mutableStateOf(initial.estMin.toString()) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var projectMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New task" else "Edit task") },
        confirmButton = {
            TextButton(
                onClick = {
                    val estimate = when (t.kind) {
                        com.maven.notafraud.core.model.TaskKind.STEPS -> t.steps.sumOf { it.min }.coerceAtLeast(5)
                        com.maven.notafraud.core.model.TaskKind.PRACTICE -> t.weeklyTargetMin ?: t.estMin
                        else -> est.toIntOrNull()?.coerceIn(5, 6000) ?: t.estMin
                    }
                    onSave(t.copy(title = t.title.trim(), estMin = estimate, weeklyTargetMin = if (t.kind == com.maven.notafraud.core.model.TaskKind.PRACTICE) (t.weeklyTargetMin ?: 120) else t.weeklyTargetMin))
                },
                enabled = t.title.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = Palette.red) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(t.title, { t = t.copy(title = it) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())

                ExposedDropdownMenuBox(expanded = projectMenu, onExpandedChange = { projectMenu = it }) {
                    OutlinedTextField(
                        value = data.projects.firstOrNull { it.id == t.projectId }?.name ?: "No project",
                        onValueChange = {}, readOnly = true, label = { Text("Project") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(projectMenu) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                        DropdownMenuItem(text = { Text("No project") }, onClick = { t = t.copy(projectId = null); projectMenu = false })
                        data.projects.filterNot { it.archived }.forEach { p ->
                            DropdownMenuItem(text = { Text(p.name) }, onClick = { t = t.copy(projectId = p.id); projectMenu = false })
                        }
                    }
                }

                SectionLabel("Kind")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (k in com.maven.notafraud.core.model.TaskKind.entries) {
                        val label = when (k) {
                            com.maven.notafraud.core.model.TaskKind.FOCUS -> "focus"
                            com.maven.notafraud.core.model.TaskKind.STEPS -> "steps + waits"
                            com.maven.notafraud.core.model.TaskKind.PRACTICE -> "practice"
                            com.maven.notafraud.core.model.TaskKind.ERRAND -> "errand"
                            com.maven.notafraud.core.model.TaskKind.WAITING -> "waiting on"
                            com.maven.notafraud.core.model.TaskKind.FILLER -> "low-brain"
                        }
                        FilterChip(t.kind == k, { t = t.copy(kind = k) }, { Text(label) })
                    }
                }
                when (t.kind) {
                    com.maven.notafraud.core.model.TaskKind.STEPS -> StepsEditor(t.steps) { t = t.copy(steps = it) }
                    com.maven.notafraud.core.model.TaskKind.PRACTICE -> {
                        SectionLabel("Minutes per week")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (m in listOf(30, 60, 120, 180, 300, 420)) FilterChip(t.weeklyTargetMin == m, { t = t.copy(weeklyTargetMin = m, estMin = m) }, { Text(Time.durationText(m)) })
                        }
                        SectionLabel("Longest session")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (m in listOf(15, 30, 45, 60, 90)) FilterChip(t.maxBlockMin == m, { t = t.copy(maxBlockMin = m) }, { Text("${m}m") })
                        }
                        // One practice type (v0.11): a fixed weekly time is optional; the rest of the target fills gaps.
                        SectionLabel("Fixed weekly time (optional)")
                        val dayNames = listOf("", "M", "T", "W", "T", "F", "S", "S")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (dow in 1..7) FilterChip(dow in t.fixedDays, {
                                val days = if (dow in t.fixedDays) t.fixedDays - dow else t.fixedDays + dow
                                t = t.copy(fixedDays = days, fixedStartMin = if (days.isEmpty()) null else t.fixedStartMin ?: 17 * 60, fixedLenMin = if (days.isEmpty()) null else t.fixedLenMin ?: 60)
                            }, { Text(dayNames[dow]) })
                        }
                        if (t.fixedDays.isNotEmpty()) {
                            var pickingFixed by remember { mutableStateOf(false) }
                            TextButton(onClick = { pickingFixed = true }) { Text("At ${hm(t.fixedStartMin ?: 17 * 60)}") }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (m in listOf(15, 30, 45, 60, 90, 120)) FilterChip(t.fixedLenMin == m, { t = t.copy(fixedLenMin = m) }, { Text(Time.durationText(m)) })
                            }
                            Text("Keeps this slot every week (gives way to a hot deadline). Anything left of the weekly target goes into gaps.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                            if (pickingFixed) TimeDialog(t.fixedStartMin ?: 17 * 60, onDismiss = { pickingFixed = false }) { m -> t = t.copy(fixedStartMin = m); pickingFixed = false }
                        }
                    }
                    com.maven.notafraud.core.model.TaskKind.WAITING ->
                        OutlinedTextField(t.waitingOn, { t = t.copy(waitingOn = it) }, label = { Text("Waiting on (who/what)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    else -> Unit
                }
                if (t.kind != com.maven.notafraud.core.model.TaskKind.STEPS && t.kind != com.maven.notafraud.core.model.TaskKind.PRACTICE) {
                SectionLabel("Estimate (min)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf(15, 30, 60, 90, 120, 240)) FilterChip(est == m.toString(), { est = m.toString() }, { Text("$m") })
                }
                OutlinedTextField(est, { est = it.filter(Char::isDigit) }, label = { Text("Minutes") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }

                SectionLabel("Due")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val due = t.dueAt
                    TextButton(onClick = { pickDate = true }) {
                        Text(due?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("EEE MMM d, h:mma", Locale.US)) } ?: "No due date")
                    }
                    if (due != null) TextButton(onClick = { t = t.copy(dueAt = null) }) { Text("Clear", color = Palette.textDim) }
                }

                if (t.dueAt != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Firm deadline (pushed hard as it closes in)", Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = t.priority == Priority.HIGH, onCheckedChange = { t = t.copy(priority = if (it) Priority.HIGH else Priority.NORMAL) })
                }
                PriorityChips(t, data) { lvl -> t = t.copy(importance = lvl, hot = false, bumpUntil = null) }
                SectionLabel("Energy it takes")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (e in Energy.entries) FilterChip(t.energy == e, { t = t.copy(energy = e) }, { Text(e.name.lowercase()) })
                }
                SectionLabel("Shortest useful block")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf(10, 15, 30, 45, 60)) FilterChip(t.minBlockMin == m, { t = t.copy(minBlockMin = m) }, { Text("${m}m") })
                }
                OutlinedTextField(t.notes, { t = t.copy(notes = it) }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth())
                if (!isNew) {
                    val left = Scorer.minutesLeft(t)
                    Text("Logged ${t.loggedMin}m · $left m left", style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
                    val pushed = t.deferrals.count { it > System.currentTimeMillis() - 14 * Time.DAY }
                    if (pushed > 0) Text("Pushed back $pushed× in 14 days", style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
                    com.maven.notafraud.core.stats.Learning.typicalMin(data, t.id)?.let {
                        Text("You usually last ~${it}m on this; blocks are sized to match", style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
                    }
                }
            }
        },
    )

    if (pickDate) {
        val initialUtc = (t.dueAt?.let { Time.dateOf(it, zone) } ?: LocalDate.now(zone)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utc ->
                        val date = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                        val minute = t.dueAt?.let { Time.minuteOfDay(it, zone) } ?: (23 * 60 + 59)
                        t = t.copy(dueAt = Time.at(date, minute, zone))
                    }
                    pickDate = false
                    pickTime = true
                }) { Text("Next: time") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    if (pickTime) {
        val minute = t.dueAt?.let { Time.minuteOfDay(it, zone) } ?: (23 * 60 + 59)
        val state = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    t.dueAt?.let { due -> t = t.copy(dueAt = Time.at(Time.dateOf(due, zone), state.hour * 60 + state.minute, zone)) }
                    pickTime = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Keep 11:59pm") } },
            text = { TimePicker(state) },
        )
    }
}


/** Steps for laundry-style or research-style tasks: attended minutes, then an unattended wait before the next. */
@Composable
private fun StepsEditor(steps: List<com.maven.notafraud.core.model.TaskStep>, onChange: (List<com.maven.notafraud.core.model.TaskStep>) -> Unit) {
    SectionLabel("Steps")
    Text("Minutes = your attention. Wait = it runs without you (washer, samples). \"On time\" = must happen right when the wait ends.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
    steps.forEachIndexed { i, st ->
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (st.doneAt != null) Text("✓ ", color = Palette.green)
                OutlinedTextField(st.title, { v -> onChange(steps.map { if (it.id == st.id) it.copy(title = v) else it }) }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("Step ${i + 1}") })
                TextButton(onClick = { onChange(steps.filterNot { it.id == st.id }) }) { Text("✕", color = Palette.textDim) }
            }
            if (i > 0) {
                Text("Wait before (runs on its own)", style = MaterialTheme.typography.labelSmall, color = Palette.textDim)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (m in listOf(0, 15, 30, 45, 60, 120, 240)) FilterChip(st.waitBeforeMin == m, { onChange(steps.map { if (it.id == st.id) it.copy(waitBeforeMin = m) else it }) }, { Text(if (m == 0) "none" else Time.durationText(m)) })
                }
            }
            Text("Your time", style = MaterialTheme.typography.labelSmall, color = Palette.textDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in listOf(5, 10, 15, 30, 45, 60)) FilterChip(st.min == m, { onChange(steps.map { if (it.id == st.id) it.copy(min = m) else it }) }, { Text("${m}m") })
            }
            if (i > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("On time", Modifier.weight(1f))
                androidx.compose.material3.Switch(st.exact, { v -> onChange(steps.map { if (it.id == st.id) it.copy(exact = v) else it }) })
            }
        }
    }
    TextButton(onClick = { onChange(steps + com.maven.notafraud.core.model.TaskStep(title = "", min = 10, waitBeforeMin = if (steps.isEmpty()) 0 else 30)) }) { Text("Add step") }
}


/** Must / High / Normal / Low, or the area's level. Pick 3 always beats these for that day. */
@Composable
fun PriorityChips(t: Task, data: AppData, onPick: (Int?) -> Unit) {
    val areaLevel = com.maven.notafraud.core.plan.Importance.level(t.copy(importance = null), data, System.currentTimeMillis())
    SectionLabel("Priority")
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(t.importance == null, { onPick(null) }, { Text("Area (${com.maven.notafraud.core.plan.Importance.name(areaLevel)})") })
        for (lvl in com.maven.notafraud.core.plan.Importance.LEVELS) {
            val sel = t.importance?.let { if (it <= 2) 2 else it } == lvl
            FilterChip(sel, { onPick(lvl) }, { Text(com.maven.notafraud.core.plan.Importance.name(lvl)) })
        }
    }
}


@Composable
private fun ProjectDialog(data: AppData, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var area by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                SectionLabel("Area")
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(area == null, { area = null }, { Text("None") })
                    for (a in data.areas.filter { !it.archived }.sortedBy { it.order }) FilterChip(area == a.id, { area = a.id }, { Text(a.name) })
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), area) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
