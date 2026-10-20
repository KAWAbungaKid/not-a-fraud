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
    var mapMode by rememberSaveable { mutableStateOf(true) }
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
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mapMode, onClick = { mapMode = true }, label = { Text("Map") })
            FilterChip(selected = !mapMode, onClick = { mapMode = false }, label = { Text("List") })
        }
        if (mapMode) {
            MapView(data, now, onEditTask = { editing = it }, onNewTask = { pid -> editing = Task(title = "", projectId = pid, createdAt = Graph.now()) })
        } else Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                OutlinedTextField(
                    query, { query = it }, singleLine = true, placeholder = { Text("Search tasks and projects") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showDone, onClick = { showDone = false }, label = { Text("Open (${data.tasks.count { it.status == TaskStatus.TODO }})") })
                    FilterChip(selected = showDone, onClick = { showDone = true }, label = { Text("Done") })
                }
            }

            val ideas = data.ideas.filter { it.promotedTaskId == null }
            if (!showDone && q.isEmpty() && ideas.isNotEmpty()) {
                item { SectionLabel("Ideas waiting for Friday") }
                items(ideas, key = { "idea-" + it.id }) { idea ->
                    Panel {
                        Text(idea.text)
                        Row {
                            TextButton(onClick = { Graph.changeAndReplan(ctx) { Actions.promoteIdea(it, idea.id, Graph.now()) } }) { Text("Schedule for craft block") }
                            TextButton(onClick = { Graph.update { Actions.deleteIdea(it, idea.id) } }) { Text("Drop", color = Palette.textDim) }
                        }
                    }
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
        FloatingActionButton(
            onClick = { editing = Task(title = "", createdAt = Graph.now()) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, "Add task") }
        }
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
                    t.priority.takeIf { it != Priority.NORMAL }?.name?.lowercase(),
                    if (t.hot) "hot" else null,
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
                onClick = { onSave(t.copy(title = t.title.trim(), estMin = est.toIntOrNull()?.coerceIn(5, 6000) ?: t.estMin)) },
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

                SectionLabel("Estimate (min)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf(15, 30, 60, 90, 120, 240)) FilterChip(est == m.toString(), { est = m.toString() }, { Text("$m") })
                }
                OutlinedTextField(est, { est = it.filter(Char::isDigit) }, label = { Text("Minutes") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                SectionLabel("Due")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val due = t.dueAt
                    TextButton(onClick = { pickDate = true }) {
                        Text(due?.let { Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ofPattern("EEE MMM d, h:mma", Locale.US)) } ?: "No due date")
                    }
                    if (due != null) TextButton(onClick = { t = t.copy(dueAt = null) }) { Text("Clear", color = Palette.textDim) }
                }

                SectionLabel("Priority")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (p in Priority.entries) FilterChip(t.priority == p, { t = t.copy(priority = p) }, { Text(p.name.lowercase()) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pin as hot (planner pushes it up)", Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = t.hot, onCheckedChange = { t = t.copy(hot = it) })
                }
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
