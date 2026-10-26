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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.Horizon
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskStatus
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.store.Actions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAYFMT = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

fun horizonName(h: Horizon) = when (h) {
    Horizon.ASAP -> "ASAP"
    Horizon.ON_DAY -> "On a day"
    Horizon.WITHIN -> "Within dates"
    Horizon.SOMEDAY -> "Try eventually"
}

/** General to-do list: ASAP / on a set day / within dates / something to try eventually. Everything but "someday" gets planned. */
@Composable
fun TodoScreen(data: AppData, now: Long) {
    val ctx = LocalContext.current
    val zone = Time.zone(data.settings.zoneId)
    val todos = data.tasks.filter { it.horizon != null }
    val open = todos.filter { it.status == TaskStatus.TODO }
    val recentDone = todos.filter { it.status == TaskStatus.DONE && (it.doneAt ?: 0) > now - 3 * Time.DAY }
    var editing by remember { mutableStateOf<Task?>(null) }
    var adding by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Task?>(null) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Spacer(Modifier.height(8.dp)) }
            for (h in Horizon.entries) {
                val list = open.filter { it.horizon == h }.sortedBy { it.dueAt ?: it.createdAt }
                if (list.isEmpty()) continue
                item(key = "h-$h") { SectionLabel(horizonName(h) + " · ${list.size}") }
                items(list, key = { it.id }) { t -> TodoRow(t, zone, now, onCheck = { Graph.changeAndReplan(ctx) { Actions.setDone(it, t.id, true, Graph.now()) } }) { editing = t } }
            }
            if (open.isEmpty()) item {
                Text("Nothing on the list. Tap + to add: ASAP, on a set day, within some dates, or something to try eventually.", color = Palette.textDim)
            }
            if (recentDone.isNotEmpty()) {
                item { SectionLabel("Done (last 3 days)") }
                items(recentDone, key = { "d" + it.id }) { t ->
                    TodoRow(t, zone, now, onCheck = { Graph.changeAndReplan(ctx) { Actions.setDone(it, t.id, false, Graph.now()) } }) { editing = t }
                }
            }
            item {
                Text("ASAP, day and date-range items go into the plan like any task. \"Try eventually\" just waits here until you move it up.",
                    color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
            }
            item { Spacer(Modifier.height(88.dp)) }
        }
        FloatingActionButton(onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Filled.Add, "Add to-do") }
    }

    if (adding) TodoDialog(data, null, onDismiss = { adding = false }) { title, h, day, from, to, est ->
        Graph.changeAndReplan(ctx) { Actions.addTodo(it, title, h, day, from, to, est, Graph.now()) }; adding = false
    }
    editing?.let { t ->
        TodoDialog(data, t, onDismiss = { editing = null }, onMore = { details = t; editing = null }, onDelete = {
            Graph.changeAndReplan(ctx) { Actions.deleteTask(it, t.id) }; editing = null
        }) { title, h, day, from, to, est ->
            Graph.changeAndReplan(ctx) { d ->
                Actions.setHorizon(d.copy(tasks = d.tasks.map { if (it.id == t.id) it.copy(title = title, estMin = est) else it }), t.id, h, day, from, to)
            }
            editing = null
        }
    }
    details?.let { t ->
        TaskEditor(t, data, isNew = false, onDismiss = { details = null },
            onSave = { nt -> Graph.changeAndReplan(ctx) { Actions.updateTask(it, nt) }; details = null },
            onDelete = { Graph.changeAndReplan(ctx) { Actions.deleteTask(it, t.id) }; details = null })
    }
}

@Composable
private fun TodoRow(t: Task, zone: java.time.ZoneId, now: Long, onCheck: () -> Unit, onTap: () -> Unit) {
    val done = t.status == TaskStatus.DONE
    Row(Modifier.fillMaxWidth().clickable(onClick = onTap), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(done, { onCheck() })
        Column(Modifier.weight(1f)) {
            Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis, textDecoration = if (done) TextDecoration.LineThrough else null)
            val whenText = when (t.horizon) {
                Horizon.ON_DAY -> t.notBefore?.let { Time.dateOf(it, zone).format(DAYFMT) }
                Horizon.WITHIN -> listOfNotNull(t.notBefore, t.dueAt).map { Time.dateOf(it, zone).format(DAYFMT) }.joinToString(" – ")
                else -> null
            }
            val late = !done && (t.dueAt ?: Long.MAX_VALUE) < now
            Text(listOfNotNull(whenText, Time.durationText(t.estMin), if (late) "past its date" else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = if (late) Palette.amber else Palette.textDim)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoDialog(
    data: AppData, initial: Task?, onDismiss: () -> Unit, onMore: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
    onSave: (String, Horizon, LocalDate?, LocalDate?, LocalDate?, Int) -> Unit,
) {
    val zone = Time.zone(data.settings.zoneId)
    val today = Graph.today()
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var h by remember { mutableStateOf(initial?.horizon ?: Horizon.ASAP) }
    var day by remember { mutableStateOf(initial?.takeIf { it.horizon == Horizon.ON_DAY }?.notBefore?.let { Time.dateOf(it, zone) } ?: today) }
    var from by remember { mutableStateOf(initial?.takeIf { it.horizon == Horizon.WITHIN }?.notBefore?.let { Time.dateOf(it, zone) } ?: today) }
    var to by remember { mutableStateOf(initial?.takeIf { it.horizon == Horizon.WITHIN }?.dueAt?.let { Time.dateOf(it, zone) } ?: today.plusDays(6)) }
    var est by remember { mutableStateOf(initial?.estMin ?: 30) }
    var picking by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add to-do" else "To-do") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("What") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SectionLabel("When")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (x in Horizon.entries) FilterChip(h == x, { h = x }, { Text(horizonName(x)) })
                }
                when (h) {
                    Horizon.ON_DAY -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (i in 0..6) {
                                val dd = today.plusDays(i.toLong())
                                FilterChip(day == dd, { day = dd }, { Text(when (i) { 0 -> "Today"; 1 -> "Tomorrow"; else -> dd.format(DateTimeFormatter.ofPattern("EEE", Locale.US)) }) })
                            }
                        }
                        TextButton(onClick = { picking = "day" }) { Text("Other day: " + day.format(DAYFMT)) }
                    }
                    Horizon.WITHIN -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(false, { from = today; to = today.plusDays(2) }, { Text("Next 3 days") })
                            FilterChip(false, { from = today; to = today.with(java.time.DayOfWeek.SUNDAY) }, { Text("This week") })
                            FilterChip(false, { from = today; to = today.plusDays(13) }, { Text("2 weeks") })
                            FilterChip(false, { from = today; to = today.withDayOfMonth(today.lengthOfMonth()) }, { Text("This month") })
                        }
                        Row {
                            TextButton(onClick = { picking = "from" }) { Text("From " + from.format(DAYFMT)) }
                            TextButton(onClick = { picking = "to" }) { Text("to " + to.format(DAYFMT)) }
                        }
                    }
                    Horizon.ASAP -> Text("Goes to the front of the plan.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                    Horizon.SOMEDAY -> Text("Kept on the list, never planned, until you move it up.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                }
                SectionLabel("About how long")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf(10, 15, 30, 60, 120, 240)) FilterChip(est == m, { est = m }, { Text(Time.durationText(m)) })
                }
                Row {
                    if (onMore != null) TextButton(onClick = onMore) { Text("More details…") }
                    if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = Palette.red) }
                }
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank() && (h != Horizon.WITHIN || !to.isBefore(from)), onClick = { onSave(title.trim(), h, day, from, to, est) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    picking?.let { which ->
        val init = when (which) { "day" -> day; "from" -> from; else -> to }
        val state = rememberDatePickerState(initialSelectedDateMillis = init.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utc ->
                        val dd = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                        when (which) { "day" -> day = dd; "from" -> { from = dd; if (to.isBefore(dd)) to = dd }; else -> to = dd }
                    }
                    picking = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
