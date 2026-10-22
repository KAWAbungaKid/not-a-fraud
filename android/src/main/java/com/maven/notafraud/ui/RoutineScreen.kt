@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maven.notafraud.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.RoutineCategory
import com.maven.notafraud.core.model.RoutineItem
import com.maven.notafraud.core.model.RoutineTrigger
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.routine.Mood
import com.maven.notafraud.core.routine.RoutinePolicy
import com.maven.notafraud.core.routine.RoutineStatus
import com.maven.notafraud.core.store.Actions
import java.time.format.DateTimeFormatter
import java.util.Locale

fun moodColor(m: Mood): Color = when (m) {
    Mood.DONE -> Palette.green
    Mood.DUE -> Color(0xFF6EC8FF)
    Mood.LATE -> Palette.amber
    Mood.ANGRY -> Palette.red
    Mood.MISSED -> Palette.red
    Mood.CALM, Mood.OFF -> Palette.textDim
}

fun hm(m: Int): String {
    val h = (m / 60) % 24
    return "%d:%02d%s".format((h + 11) % 12 + 1, m % 60, if (h < 12) "a" else "p")
}

@Composable
fun RoutineScreen(data: AppData, now: Long) {
    val ctx = LocalContext.current
    val zone = Time.zone(data.settings.zoneId)
    val today = Time.dateOf(now, zone)
    val statuses = remember(data, now / 60_000) { RoutinePolicy.statuses(data, now).filter { it.mood != Mood.OFF } }
    var editing by remember { mutableStateOf<RoutineItem?>(null) }
    var noteFor by remember { mutableStateOf<RoutineStatus?>(null) }
    val done = statuses.count { it.mood == Mood.DONE }
    val angry = statuses.count { it.mood == Mood.ANGRY }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(today.format(DateTimeFormatter.ofPattern("EEEE", Locale.US)) + " routine · $done/${statuses.size}", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (angry > 0) "$angry overdue. Knock those out first." else "Tap to check off. Long gaps get loud.",
                    color = if (angry > 0) Palette.red else Palette.textDim,
                )
            }
            items(statuses, key = { it.item.id }) { st ->
                RoutineRow(st, data, zone, onToggle = {
                    if (st.mood == Mood.DONE) Graph.update { Actions.uncheckRoutine(it, st.item.id, today) }
                    else if (st.item.weeklyTarget != null) noteFor = st
                    else Graph.update { Actions.checkRoutine(it, st.item.id, Graph.now()) }
                    Graph.refresh(ctx)
                }, onEdit = { editing = st.item })
            }
            item { History(data, today) }
            item { Spacer(Modifier.height(88.dp)) }
        }
        FloatingActionButton(
            onClick = { editing = RoutineItem(title = "", startMin = 12 * 60, dueMin = 13 * 60, angryMin = 14 * 60) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, "Add routine item") }
    }

    noteFor?.let { st ->
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noteFor = null },
            title = { Text(st.item.title) },
            text = {
                Column {
                    Text("What was it? Sports (climbing, hike, swim, breaking…) count once you've done ${RoutinePolicy.STANDARD_FIRST} standard workouts this week.")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (o in listOf("Lift", "Run", "Mobility", "Climbing", "Sport", "Hike", "Swim", "Breaking")) FilterChip(note == o, { note = o }, { Text(o) })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Graph.update { Actions.checkRoutine(it, st.item.id, Graph.now(), note) }
                    Graph.refresh(ctx); noteFor = null
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { noteFor = null }) { Text("Cancel") } },
        )
    }

    editing?.let { item ->
        RoutineEditor(
            item, isNew = data.routine.none { it.id == item.id },
            onDismiss = { editing = null },
            onSave = { Graph.changeAndReplan(ctx) { d -> Actions.upsertRoutine(d, it) }; editing = null },
            onDelete = { Graph.changeAndReplan(ctx) { d -> Actions.deleteRoutine(d, item.id) }; editing = null },
        )
    }
}

@Composable
private fun RoutineRow(st: RoutineStatus, d: AppData, zone: java.time.ZoneId, onToggle: () -> Unit, onEdit: () -> Unit) {
    val i = st.item
    val late = RoutinePolicy.doneLate(d, st)
    val color = if (st.mood == Mood.DONE && late) Palette.amber else moodColor(st.mood)
    Panel(Modifier.clickable(onClick = onEdit), highlight = st.mood == Mood.ANGRY) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = st.mood == Mood.DONE, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(i.title, fontWeight = if (st.mood == Mood.ANGRY) FontWeight.Bold else FontWeight.Normal)
                val line = when (st.mood) {
                    Mood.DONE -> "Done ${Time.clockAmPm(st.doneAt!!, zone)}" + (if (late) " · late (due ${hm(i.dueMin)})" else " · on time") + (st.note?.let { " · $it" } ?: "")
                    Mood.CALM -> st.note ?: "${hm(i.startMin)}–${hm(i.dueMin)}"
                    Mood.DUE -> "Now · by ${hm(i.dueMin)}"
                    Mood.LATE -> "${Time.durationText(st.minutesLate)} late"
                    Mood.ANGRY -> "${Time.durationText(st.minutesLate)} late. Do it now."
                    else -> ""
                } + (st.weekDone?.let { w ->
                    val tally = RoutinePolicy.weekTally(d, i, Time.dateOf(System.currentTimeMillis(), zone))
                    " · $w/${i.weeklyTarget} this week" + if (tally.sport > 0) " (${tally.standard} standard + ${tally.sport} sport" + (if (tally.standard < RoutinePolicy.STANDARD_FIRST) ", sport counts after ${RoutinePolicy.STANDARD_FIRST} standard)" else ")") else ""
                } ?: "") +
                    (if (i.trigger == RoutineTrigger.BEDTIME && st.mood != Mood.DONE) " · checks when you go to bed" else "")
                Text(line, color = color, style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
    }
}

/** Last 7 days, one row per item: filled = done. */
@Composable
private fun History(d: AppData, today: java.time.LocalDate) {
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    Panel {
        SectionLabel("Last 7 days")
        Row {
            Spacer(Modifier.weight(1f))
            days.forEach { Text(it.dayOfWeek.name.take(1), Modifier.size(22.dp), color = Palette.textDim, style = MaterialTheme.typography.labelSmall) }
        }
        d.routine.filter { it.enabled }.sortedBy { it.order }.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                days.forEach { day ->
                    val rd = RoutinePolicy.day(d, day)
                    val applies = RoutinePolicy.appliesOn(item, day)
                    val c = when {
                        rd.done.containsKey(item.id) -> Palette.green
                        !applies -> Color.Transparent
                        day == today -> Palette.surfaceHigh
                        item.weeklyTarget != null -> Palette.surfaceHigh
                        else -> Palette.red.copy(alpha = 0.6f)
                    }
                    Box(Modifier.padding(3.dp).size(16.dp).clip(CircleShape).background(c))
                }
            }
        }
    }
}

@Composable
private fun RoutineEditor(initial: RoutineItem, isNew: Boolean, onDismiss: () -> Unit, onSave: (RoutineItem) -> Unit, onDelete: () -> Unit) {
    var r by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf<String?>(null) }
    val names = listOf("", "M", "T", "W", "T", "F", "S", "S")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New routine item" else "Edit routine item") },
        confirmButton = {
            TextButton(onClick = { onSave(r.copy(title = r.title.trim())) }, enabled = r.title.isNotBlank() && r.startMin <= r.dueMin && r.dueMin <= r.angryMin) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = Palette.red) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(r.title, { r = r.copy(title = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SectionLabel("When")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { picking = "start" }) { Text("From ${hm(r.startMin)}") }
                    TextButton(onClick = { picking = "due" }) { Text("Due ${hm(r.dueMin)}") }
                    TextButton(onClick = { picking = "angry" }) { Text("Angry ${hm(r.angryMin)}") }
                }
                Text("Due = late after this (one nudge). Angry = nudges every 30 min.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                SectionLabel("Days")
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (dow in 1..7) FilterChip(dow in r.days, { r = r.copy(days = if (dow in r.days) r.days - dow else r.days + dow) }, { Text(names[dow]) })
                }
                SectionLabel("Type")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoutineCategory.entries.forEach { c -> FilterChip(r.category == c, { r = r.copy(category = c) }, { Text(c.name.lowercase()) }) }
                }
                SectionLabel("Checks itself when")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(r.autoCheck == null, { r = r.copy(autoCheck = null) }, { Text("only by hand") })
                    for ((a, label) in listOf(
                        com.maven.notafraud.core.model.AutoCheck.WAKE to "you wake up (watch / first unlock)",
                        com.maven.notafraud.core.model.AutoCheck.WORKOUT to "a workout is logged",
                        com.maven.notafraud.core.model.AutoCheck.PICK3 to "you pick tomorrow's 3",
                    )) FilterChip(r.autoCheck == a, { r = r.copy(autoCheck = a) }, { Text(label) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Also check when going to bed", Modifier.weight(1f))
                    Switch(r.trigger == RoutineTrigger.BEDTIME, { r = r.copy(trigger = if (it) RoutineTrigger.BEDTIME else RoutineTrigger.TIME) })
                }
                SectionLabel("Block time in the plan")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf(0, 15, 20, 30, 45, 60, 90)) FilterChip(r.reserveMin == m, { r = r.copy(reserveMin = m) }, { Text(if (m == 0) "none" else "${m}m") })
                }
                SectionLabel("Times per week")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(r.weeklyTarget == null, { r = r.copy(weeklyTarget = null) }, { Text("every day listed") })
                    for (n in 2..7) FilterChip(r.weeklyTarget == n, { r = r.copy(weeklyTarget = n) }, { Text("$n") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("On", Modifier.weight(1f))
                    Switch(r.enabled, { r = r.copy(enabled = it) })
                }
            }
        },
    )
    picking?.let { which ->
        val cur = when (which) { "start" -> r.startMin; "due" -> r.dueMin; else -> r.angryMin }
        TimeDialog(cur, onDismiss = { picking = null }) { m ->
            r = when (which) {
                "start" -> r.copy(startMin = m, dueMin = maxOf(m, r.dueMin), angryMin = maxOf(m, r.angryMin))
                "due" -> r.copy(dueMin = m, angryMin = maxOf(m, r.angryMin), startMin = minOf(m, r.startMin))
                else -> r.copy(angryMin = m, dueMin = minOf(m, r.dueMin), startMin = minOf(m, r.startMin))
            }
            picking = null
        }
    }
}

@Composable
fun TimeDialog(minute: Int, onDismiss: () -> Unit, onSet: (Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = (minute / 60) % 24, initialMinute = minute % 60, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSet(state.hour * 60 + state.minute) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state) },
    )
}
