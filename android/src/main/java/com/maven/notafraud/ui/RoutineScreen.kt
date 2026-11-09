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
import androidx.compose.foundation.layout.heightIn
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
    var breaking by remember { mutableStateOf<RoutineStatus?>(null) }
    val twigNags = remember(data, now / 60_000) { com.maven.notafraud.core.diet.DietLink.extraNags(data, now) }
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
            val diet = com.maven.notafraud.core.diet.DietLink.today(data, now)
            if (diet != null && (twigNags.isNotEmpty() || data.settings.dietNags)) item {
                Panel(highlight = twigNags.any { it.level >= 2 }) {
                    SectionLabel("Not a Twig")
                    Text(com.maven.notafraud.core.diet.DietLink.summary(diet), color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                    for (n in twigNags) Text(
                        "${n.title}: ${n.text}",
                        color = if (n.level >= 2) Palette.red else Palette.amber, style = MaterialTheme.typography.bodySmall,
                    )
                    val paused = data.routine.filter { it.pausedByDiet }
                    if (paused.isNotEmpty()) Text(
                        "Meal times come from Twig. Paused (no Twig slot): " + paused.joinToString { it.title } + ". Add the slot in Twig, or link it to one, to bring it back.",
                        color = Palette.textDim, style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { com.maven.notafraud.sense.DietSync.open(ctx) }) { Text("Open Not a Twig") }
                }
            }
            items(statuses, key = { it.item.id }) { st ->
                RoutineRow(st, data, zone, onToggle = {
                    if (st.item.category == RoutineCategory.STREAK) {
                        if (st.mood == Mood.MISSED) Graph.update { Actions.unbreakStreak(it, st.item.id, today) } else breaking = st
                    } else if (st.mood == Mood.DONE) Graph.update { Actions.uncheckRoutine(it, st.item.id, today) }
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
                    val trog = data.workout != null && st.item.autoCheck == com.maven.notafraud.core.model.AutoCheck.WORKOUT
                    Text(
                        if (trog) "Not a Troglodyte ticks this when you finish a workout there. Climbing and other sports count as cardio, not toward the workout minimum."
                        else "What was it? Sports (climbing, hike, swim, breaking…) count once you've done ${RoutinePolicy.STANDARD_FIRST} standard workouts this week.",
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (o in if (trog) listOf("Lift", "Mobility") else listOf("Lift", "Run", "Mobility", "Climbing", "Sport", "Hike", "Swim", "Breaking")) FilterChip(note == o, { note = o }, { Text(o) })
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

    breaking?.let { st ->
        AlertDialog(
            onDismissRequest = { breaking = null },
            title = { Text("Broke it: ${st.item.title}?") },
            text = { Text("${st.streak ?: 0} days in a row so far. Saying so resets the count; the best run (${st.best ?: 0}) stays on record. Honest beats perfect.") },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        Graph.update { Actions.breakStreak(it, st.item.id, today.minusDays(1), Graph.now()) }
                        Graph.refresh(ctx); breaking = null
                    }) { Text("Yesterday") }
                    TextButton(onClick = {
                        Graph.update { Actions.breakStreak(it, st.item.id, today, Graph.now()) }
                        Graph.refresh(ctx); breaking = null
                    }) { Text("Today", color = Palette.red) }
                }
            },
            dismissButton = { TextButton(onClick = { breaking = null }) { Text("Cancel") } },
        )
    }

    editing?.let { item ->
        RoutineEditor(
            item, isNew = data.routine.none { it.id == item.id },
            onDismiss = { editing = null },
            onSave = { saved ->
                val r = if (saved.category == RoutineCategory.STREAK && saved.streakSince == null) saved.copy(streakSince = today.toString(), reserveMin = 0, weeklyTarget = null, autoCheck = null) else saved
                // Both apps installed: a linked meal's time or slot goes to Not a Twig too (one schedule).
                if (data.diet != null && r.category == RoutineCategory.MEAL && r.dietSlot != null &&
                    (r.dietSlot != item.dietSlot || r.dueMin != item.dueMin || item.category != RoutineCategory.MEAL)
                ) {
                    val old = item.dietSlot?.takeIf { item.category == RoutineCategory.MEAL && data.routine.any { it.id == item.id } }
                    com.maven.notafraud.sense.DietSync.pushMeal(ctx, old, r.dietSlot, r.dueMin)
                }
                // Switching an item off loosens the routine: with the settings lock on it waits for the morning.
                val queueOff = item.enabled && !r.enabled && data.settings.settingsLock && data.routine.any { it.id == item.id }
                Graph.changeAndReplan(ctx) { d ->
                    val saved2 = Actions.upsertRoutine(d, r.copy(pausedByDiet = false, enabled = if (queueOff) true else r.enabled))
                    if (queueOff) com.maven.notafraud.core.store.Lock.removeRoutine(saved2, item.id, delete = false, now = Graph.now()) else saved2
                }
                editing = null
                Thread { com.maven.notafraud.sense.DietSync.sync(ctx); Graph.refresh(ctx) }.start()
            },
            onDelete = {
                if (data.settings.settingsLock) {
                    // Deleting a routine item waits for the morning like any other loosening (and asks why).
                    Graph.update { d -> com.maven.notafraud.core.store.Lock.removeRoutine(d, item.id, delete = true, now = Graph.now()) }
                } else {
                    if (data.diet != null && item.category == RoutineCategory.MEAL && item.dietSlot != null) com.maven.notafraud.sense.DietSync.pushMeal(ctx, item.dietSlot, null, null, remove = true)
                    Graph.changeAndReplan(ctx) { d -> Actions.deleteRoutine(d, item.id) }
                }
                editing = null
            },
        )
    }
}

@Composable
private fun RoutineRow(st: RoutineStatus, d: AppData, zone: java.time.ZoneId, onToggle: () -> Unit, onEdit: () -> Unit) {
    val i = st.item
    // Meals Not a Twig owns keep Twig's times, not this item's.
    val late = RoutinePolicy.doneLate(d, st) && !(st.fromDiet || st.note == "Not a Twig")
    val color = if (st.mood == Mood.DONE && late) Palette.amber else moodColor(st.mood)
    if (i.category == RoutineCategory.STREAK) {
        StreakRow(st, onToggle, onEdit)
        return
    }
    Panel(Modifier.clickable(onClick = onEdit), highlight = st.mood == Mood.ANGRY) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = st.mood == Mood.DONE, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(i.title, fontWeight = if (st.mood == Mood.ANGRY) FontWeight.Bold else FontWeight.Normal)
                val line = when (st.mood) {
                    Mood.DONE -> if (st.doneAt == null) "Done" + (st.note?.let { " · $it" } ?: "") else "Done ${Time.clockAmPm(st.doneAt!!, zone)}" + (if (late) " · late (due ${hm(i.dueMin)})" else " · on time") + (st.note?.let { " · $it" } ?: "")
                    Mood.CALM -> st.note ?: "${hm(i.startMin)}–${hm(i.dueMin)}"
                    Mood.DUE -> if (st.fromDiet) st.note ?: "Now" else "Now · by ${hm(i.dueMin)}"
                    Mood.LATE -> if (st.fromDiet) st.note ?: "Late" else "${Time.durationText(st.minutesLate)} late"
                    Mood.ANGRY -> if (st.fromDiet) (st.note ?: "Late") + " Do it now." else "${Time.durationText(st.minutesLate)} late. Do it now."
                    else -> ""
                } + (st.weekDone?.let { w ->
                    val tally = RoutinePolicy.weekTally(d, i, Time.dateOf(System.currentTimeMillis(), zone))
                    " · $w/${i.weeklyTarget} this week" + if (tally.sport > 0) " (${tally.standard} standard + ${tally.sport} sport" + (if (tally.standard < RoutinePolicy.STANDARD_FIRST) ", sport counts after ${RoutinePolicy.STANDARD_FIRST} standard)" else ")") else ""
                } ?: "") +
                    (if (i.trigger == RoutineTrigger.BEDTIME && st.mood != Mood.DONE) " · checks when you go to bed" else "") +
                    (if (i.openPkg != null && st.mood != Mood.DONE) " · opens " + com.maven.notafraud.sense.AppLabels.label(LocalContext.current, i.openPkg!!) + " on unlock" else "")
                Text(line, color = color, style = MaterialTheme.typography.bodySmall)
            }
            // How long you've been consistent: days (or weeks, for weekly targets) in a row.
            val c = remember(d.routineDays, i) { RoutinePolicy.consistency(d, i, Time.dateOf(System.currentTimeMillis(), zone)) }
            if (c.count > 0) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("${c.count}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = if (c.count >= c.best && c.count >= 3) Palette.green else MaterialTheme.colorScheme.onSurface)
                Text(if (c.weeks) "wk" else "d", style = MaterialTheme.typography.labelSmall, color = Palette.textDim)
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
    }
}

/** Streak: counts by itself; one button to say it broke (or undo today's break). */
@Composable
private fun StreakRow(st: RoutineStatus, onToggle: () -> Unit, onEdit: () -> Unit) {
    val broken = st.mood == Mood.MISSED
    Panel(Modifier.clickable(onClick = onEdit), highlight = broken) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${st.streak ?: 0}",
                Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.titleLarge,
                color = if (broken) Palette.red else Palette.green,
                fontWeight = FontWeight.Bold,
            )
            Column(Modifier.weight(1f)) {
                Text(st.item.title)
                Text(st.note ?: "", color = if (broken) Palette.red else Palette.textDim, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onToggle) { Text(if (broken) "Undo" else "Broke it", color = if (broken) Palette.textDim else Palette.red) }
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
                        item.category == RoutineCategory.STREAK -> when {
                            item.id in rd.broken -> Palette.red.copy(alpha = 0.6f)
                            RoutinePolicy.streakHeld(d, item, day) -> Palette.green
                            else -> Color.Transparent
                        }
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
            TextButton(
                onClick = { onSave(r.copy(title = r.title.trim())) },
                enabled = r.title.isNotBlank() && (r.category == RoutineCategory.STREAK || (r.startMin <= r.dueMin && r.dueMin <= r.angryMin)),
            ) { Text("Save") }
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
                SectionLabel("Type")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RoutineCategory.entries.forEach { c ->
                        FilterChip(r.category == c, {
                            r = r.copy(category = c, dietSlot = if (c == RoutineCategory.MEAL) r.dietSlot ?: com.maven.notafraud.core.diet.DietLink.guessSlot(r.title) else null)
                        }, { Text(c.name.lowercase()) })
                    }
                }
                if (r.category == RoutineCategory.STREAK) {
                    Text("Counts as done every day by itself. No times, no nags: you only tap \"Broke it\" when you break it.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                    val today = Time.dateOf(System.currentTimeMillis(), Time.zone(Graph.data.settings.zoneId))
                    val already = r.streakSince?.let { runCatching { java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(it), today).toInt() + 1 }.getOrNull() } ?: 1
                    var text by remember { mutableStateOf(already.toString()) }
                    OutlinedTextField(
                        text,
                        { v ->
                            text = v.filter { it.isDigit() }.take(5)
                            text.toIntOrNull()?.takeIf { it >= 1 }?.let { n -> r = r.copy(streakSince = today.minusDays((n - 1).toLong()).toString()) }
                        },
                        label = { Text("Days in a row so far (today counts)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    SectionLabel("Days it applies")
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (dow in 1..7) FilterChip(dow in r.days, { r = r.copy(days = if (dow in r.days) r.days - dow else r.days + dow) }, { Text(names[dow]) })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("On", Modifier.weight(1f))
                        Switch(r.enabled, { r = r.copy(enabled = it) })
                    }
                } else {
                if (r.category == RoutineCategory.MEAL) {
                    SectionLabel("Not a Twig meal slot")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(r.dietSlot == null, { r = r.copy(dietSlot = null) }, { Text("none") })
                        for (slot in com.maven.notafraud.core.diet.DietLink.KNOWN_SLOTS) FilterChip(r.dietSlot.equals(slot, ignoreCase = true), { r = r.copy(dietSlot = slot) }, { Text(slot) })
                    }
                    Text(
                        if (Graph.data.diet != null) "Synced with Not a Twig: the due time below is the slot's time in Twig (change it in either app). Logging that slot ticks this."
                        else "Linked: once Not a Twig is installed, this follows its slot's time and logging there ticks it.",
                        color = Palette.textDim, style = MaterialTheme.typography.bodySmall,
                    )
                }
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
                SectionLabel("Checks itself when")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(r.autoCheck == null, { r = r.copy(autoCheck = null) }, { Text("only by hand") })
                    for ((a, label) in listOf(
                        com.maven.notafraud.core.model.AutoCheck.WAKE to "you wake up (watch / first unlock)",
                        com.maven.notafraud.core.model.AutoCheck.WORKOUT to "a workout is logged",
                        com.maven.notafraud.core.model.AutoCheck.CARDIO to "cardio or a sport session (climbing…)",
                        com.maven.notafraud.core.model.AutoCheck.PICK3 to "you pick tomorrow's 3",
                    )) FilterChip(r.autoCheck == a, { r = r.copy(autoCheck = a) }, { Text(label) })
                }
                OpenAppSection(r) { r = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Also check when going to bed", Modifier.weight(1f))
                    Switch(r.trigger == RoutineTrigger.BEDTIME, { r = r.copy(trigger = if (it) RoutineTrigger.BEDTIME else RoutineTrigger.TIME) })
                }
                SectionLabel("Block time in the plan")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf(0, 15, 20, 30, 45, 60, 90)) FilterChip(r.reserveMin == m, { r = r.copy(reserveMin = m) }, { Text(if (m == 0) "none" else "${m}m") })
                }
                SectionLabel("Times per week")
                val fromWorkoutApp = r.autoCheck == com.maven.notafraud.core.model.AutoCheck.WORKOUT && com.maven.notafraud.Graph.data.workout != null
                if (fromWorkoutApp) Text("Set by Not a Troglodyte (${com.maven.notafraud.Graph.data.workout?.targetPerWeek} a week, minimum ${com.maven.notafraud.Graph.data.workout?.minPerWeek}). Change it there.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(r.weeklyTarget == null, { r = r.copy(weeklyTarget = null) }, { Text("every day listed") })
                    for (n in 2..7) FilterChip(r.weeklyTarget == n, { r = r.copy(weeklyTarget = n) }, { Text("$n") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("On", Modifier.weight(1f))
                    Switch(r.enabled, { r = r.copy(enabled = it) })
                }
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

/** "Open an app when I unlock": which app, from when, at what ladder level, and whether time in it ticks the item. */
@Composable
private fun OpenAppSection(r: RoutineItem, onChange: (RoutineItem) -> Unit) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    SectionLabel("Open an app when I unlock")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(r.openPkg == null, { onChange(r.copy(openPkg = null)) }, { Text("off") })
        FilterChip(r.openPkg != null, { picking = true }, { Text(r.openPkg?.let { com.maven.notafraud.sense.AppLabels.label(ctx, it) } ?: "pick an app…") })
    }
    if (r.openPkg != null) {
        Text("Open it once it's", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((v, label) in listOf(0 to "due", 1 to "late", 2 to "angry")) FilterChip(r.openFromStage == v, { onChange(r.copy(openFromStage = v)) }, { Text(label) })
        }
        Text("Only when the brick ladder is at least", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in 0..3) FilterChip(r.openAtLevel == v, { onChange(r.copy(openAtLevel = v)) }, { Text(if (v == 0) "any level" else "${v} · ${com.maven.notafraud.core.guard.Ladder.NAMES[v]}") })
        }
        Text("Open it again after", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in listOf(5, 15, 30, 60)) FilterChip(r.openEveryMin == v, { onChange(r.copy(openEveryMin = v)) }, { Text("${v}m") })
        }
        Text("Tick it after this long in the app", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in listOf(0, 5, 10, 15, 20, 30)) FilterChip(r.openDoneMin == v, { onChange(r.copy(openDoneMin = v)) }, { Text(if (v == 0) "by hand" else "${v}m") })
        }
        Text("Unlocking while it's due takes you straight into the app (never during a call or after quiet time). This app always opens, even at Level 3 or bedtime.",
            color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
    }
    if (picking) {
        val apps = remember { com.maven.notafraud.sense.AppLabels.launchable(ctx) }
        var query by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Which app?") },
            text = {
                Column {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }, key = { it.pkg }) { app ->
                            Text(app.label, Modifier.fillMaxWidth().clickable { onChange(r.copy(openPkg = app.pkg)); picking = false }.padding(vertical = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
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
