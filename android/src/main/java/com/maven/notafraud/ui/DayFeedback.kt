@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maven.notafraud.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maven.notafraud.Graph
import com.maven.notafraud.core.guard.UnlockCheckIn
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.TaskStatus
import com.maven.notafraud.core.model.liveTasks
import com.maven.notafraud.core.plan.BlockKind
import com.maven.notafraud.core.plan.DayReview
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.store.Actions
import com.maven.notafraud.sense.DietSync
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Colour for a graded plan block: green done, blue did something else, amber partly, red missed, grey explained. */
fun markColor(m: DayReview.Mark): androidx.compose.ui.graphics.Color = when (m) {
    DayReview.Mark.DONE -> Palette.green
    DayReview.Mark.OTHER -> androidx.compose.ui.graphics.Color(0xFF6EB4FF)
    DayReview.Mark.PARTIAL -> Palette.amber
    DayReview.Mark.MISSED -> Palette.red
    DayReview.Mark.EXCUSED -> Palette.textDim
    DayReview.Mark.NOW, DayReview.Mark.LATER -> Palette.greenDim
}

fun markText(b: DayReview.BlockMark, planned: Int): String = when (b.mark) {
    DayReview.Mark.DONE -> "✓ done" + if (b.workedMin > 0) " · ${Time.durationText(b.workedMin)}" else ""
    DayReview.Mark.PARTIAL -> "◐ ${Time.durationText(b.workedMin)} of ${Time.durationText(planned)}"
    DayReview.Mark.OTHER -> "↷ did ${b.instead ?: "something else"} instead"
    DayReview.Mark.MISSED -> "✗ missed · tap to say what happened"
    DayReview.Mark.EXCUSED -> "– ${b.instead ?: "explained"}"
    DayReview.Mark.NOW -> "now"
    DayReview.Mark.LATER -> ""
}

/** Nudge a time by 5 / 15 minutes: "From 2:00 PM  −15 −5 +5 +15". */
@Composable
private fun TimeNudge(label: String, value: Long, zone: java.time.ZoneId, onChange: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ${Time.clockAmPm(value, zone)}", modifier = Modifier.weight(1f))
        for (m in listOf(-15, -5, 5, 15)) TextButton(onClick = { onChange(value + m * Time.MIN) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
            Text(if (m > 0) "+$m" else "$m", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * "I worked on…" / "I went over": log time at a past time (off the phone, or forgot Start). Then the rest of the
 * day re-plans from now: what no longer fits moves to the coming days, least important first.
 */
@Composable
fun LogTimeDialog(
    data: AppData, now: Long, start0: Long, end0: Long, taskId0: String?, heading: String = "Log time",
    onSaved: (AppData) -> AppData = { it }, onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val zone = Time.zone(data.settings.zoneId)
    var start by remember { mutableLongStateOf(start0) }
    var end by remember { mutableLongStateOf(minOf(end0, now)) }
    var taskId by remember { mutableStateOf(taskId0) }
    var other by remember { mutableStateOf("") }
    val dayStart = Time.at(Time.dateOf(now, zone), 0, zone)
    val options = remember(data) {
        val planned = data.todayPlan?.blocks.orEmpty().filter { it.kind == BlockKind.TASK }.mapNotNull { it.taskId }
        val recent = data.sessions.sortedByDescending { it.end }.mapNotNull { it.taskId }
        val ids = (listOfNotNull(taskId0) + planned + recent).distinct()
        val live = data.liveTasks().filter { it.status == TaskStatus.TODO }
        (ids.mapNotNull { id -> live.firstOrNull { it.id == id } } + com.maven.notafraud.core.score.Scorer.rank(live, now, 60, data.settings.energy).map { it.first })
            .distinctBy { it.id }.take(12)
    }
    val mins = ((end - start) / Time.MIN).toInt()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TimeNudge("From", start, zone) { start = it.coerceIn(dayStart, end - Time.MIN) }
                TimeNudge("To", end, zone) { end = it.coerceIn(start + Time.MIN, now) }
                Text(if (mins > 0) Time.durationText(mins) else "—", color = Palette.green)
                SectionLabel("On")
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(options, key = { it.id }) { t ->
                        Row(Modifier.fillMaxWidth().clickable { taskId = t.id }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(taskId == t.id, { taskId = t.id })
                            Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().clickable { taskId = null }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(taskId == null, { taskId = null })
                            OutlinedTextField(other, { other = it; taskId = null }, placeholder = { Text("Something else (trading, studying…)") }, singleLine = true)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = mins >= 1 && (taskId != null || other.isNotBlank()), onClick = {
                Graph.changeAndReplan(ctx) { onSaved(Actions.logPast(it, taskId, other, start, end, Graph.now())) }
                onDismiss()
            }) { Text("Log it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Day score, good-day streak, planned vs actual so far, and the "you said this mattered" nag. */
@Composable
fun DayScoreCard(data: AppData, now: Long, onLog: () -> Unit) {
    val ctx = LocalContext.current
    val today = Time.dateOf(now, Time.zone(data.settings.zoneId))
    val score = remember(data, now / 60_000) { DayReview.score(data, today, now) }
    val streak = remember(data, today) { DayReview.goodStreak(data, now) }
    val totals = remember(data, now / 60_000) { DayReview.totals(data, now) }
    val nag = remember(data, now / 60_000) { DayReview.priorityNag(data, now) }
    if (score == null && nag == null) return
    Panel(highlight = nag != null) {
        if (score != null) {
            val col = when { score.value >= data.settings.goodDayScore -> Palette.green; score.value >= 45 -> Palette.amber; else -> Palette.red }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${score.value}", style = MaterialTheme.typography.displaySmall.merge(Mono), color = col)
                Column(Modifier.padding(start = 10.dp).weight(1f)) {
                    Text("day score so far", style = MaterialTheme.typography.labelMedium, color = Palette.textDim)
                    Text(
                        if (streak > 0) "$streak good day${if (streak == 1) "" else "s"} in a row" + if (score.value >= data.settings.goodDayScore) " · today keeps it going" else " · ${data.settings.goodDayScore} keeps it"
                        else "Get to ${data.settings.goodDayScore} to start a streak",
                        style = MaterialTheme.typography.bodySmall, color = if (streak > 0) Palette.green else Palette.textDim,
                    )
                }
            }
            Text(score.parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
            if (totals.plannedMin > 0) Text(
                "Plan so far: ${totals.done} done" + (if (totals.other > 0) ", ${totals.other} swapped" else "") + (if (totals.missed > 0) ", ${totals.missed} missed" else ""),
                style = MaterialTheme.typography.bodySmall, color = if (totals.missed > 0) Palette.amber else Palette.textDim,
            )
        }
        nag?.let { n ->
            Text("You said this mattered", style = MaterialTheme.typography.titleSmall, color = Palette.red, modifier = Modifier.padding(top = 6.dp))
            Text(n.text, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Graph.update { Actions.startSession(it, n.task.id, null, Graph.now()) }; Graph.refresh(ctx) }) { Text("Start it now") }
                TextButton(onClick = onLog) { Text("I already did some") }
            }
        }
    }
}

/** Evening: how today went. Morning: yesterday's, until dismissed. */
@Composable
fun SummaryCard(data: AppData, now: Long) {
    val date = remember(data, now / 60_000) { DayReview.summaryDate(data, now) } ?: return
    val s = remember(data, date, now / 300_000) { DayReview.summary(data, date, now) }
    val today = Time.dateOf(now, Time.zone(data.settings.zoneId))
    Panel(highlight = true) {
        SectionLabel(if (date == today) "How today went" else "Yesterday, " + date.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)))
        s.score?.let { sc ->
            val good = sc.value >= data.settings.goodDayScore
            Text("${sc.value} · " + if (good) "a good day" else "not quite", style = MaterialTheme.typography.titleLarge, color = if (good) Palette.green else Palette.amber)
            Text(sc.parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
        }
        if (s.top.isNotEmpty()) Text("Worked on: " + s.top.joinToString(" · ") { "${it.first} ${Time.durationText(it.second)}" }, style = MaterialTheme.typography.bodySmall)
        if (s.missedBlocks.isNotEmpty()) Text("Missed: " + s.missedBlocks.take(5).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Palette.red)
        if (s.routineDue > 0) Text(
            "Routine ${s.routineDone}/${s.routineDue}" + if (s.routineMissed.isNotEmpty()) " · not done: " + s.routineMissed.take(4).joinToString { it.lowercase() } else "",
            style = MaterialTheme.typography.bodySmall, color = if (s.routineMissed.isEmpty()) Palette.green else Palette.textDim,
        )
        if (s.wastedMin > 0) Text("${Time.durationText(s.wastedMin)} in blocked apps", style = MaterialTheme.typography.bodySmall, color = if (s.wastedMin >= 45) Palette.amber else Palette.textDim)
        if (s.goodStreak > 0) Text("Good days in a row: ${s.goodStreak}", style = MaterialTheme.typography.bodySmall, color = Palette.green)
        TextButton(onClick = { Graph.update { Actions.summarySeen(it, date) } }) { Text("Got it") }
    }
}

/**
 * After a re-plan: hot / exam work that no longer fits today while protected time is still ahead. The app never
 * takes protected time by itself; this asks.
 */
@Composable
fun SqueezeCard(data: AppData, now: Long) {
    val ctx = LocalContext.current
    val sq = remember(data, now / 60_000) { DayReview.squeeze(data, now) }
    if (sq.unplaced.isEmpty() || sq.protectedAhead.isEmpty()) return
    val zone = Time.zone(data.settings.zoneId)
    val p = sq.protectedAhead.first()
    Panel(highlight = true) {
        SectionLabel("Doesn't fit today")
        Text(sq.unplaced.joinToString(" · ") { it.title }, color = Palette.amber)
        Text("Due within a day and a half. ${p.title} (${Time.clock(p.start, zone)}) is protected, so it stays unless you say so.", style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
        p.anchorId?.let { aid ->
            OutlinedButton(onClick = {
                Confirm.ask("Skip ${p.title} today?", "The time goes to ${sq.unplaced.first().title}. Only today; it's back next time.") {
                    Graph.changeAndReplan(ctx) { Actions.skipAnchor(it, aid, Time.dateOf(p.start, zone), false, Graph.now()) }
                }
            }) { Text("Use ${p.title} for it") }
        }
    }
}

/**
 * The unlock check-in: everything overdue, answerable in a tap or two. Later = hidden 20 minutes (three in a row
 * adds a ladder point). Back counts as Later.
 */
@Composable
fun CheckInSheet(data: AppData, now: Long, onClose: () -> Unit) {
    val prompts = remember(data, now / 60_000) { UnlockCheckIn.prompts(data, now) }
    var logFor by remember { mutableStateOf<UnlockCheckIn.Prompt?>(null) }
    fun later() { Graph.update { UnlockCheckIn.later(it, Graph.now()) }; onClose() }
    fun handled(key: String) = Graph.update { UnlockCheckIn.handled(it, key, Graph.now()) }
    if (prompts.isEmpty()) { androidx.compose.runtime.LaunchedEffect(Unit) { onClose() }; return }
    Dialog(onDismissRequest = { later() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Palette.bg) {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("Check in", style = MaterialTheme.typography.headlineSmall)
                    Text("Answer what you can; the rest can wait 20 minutes. Calls and emergencies are never blocked.", style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
                }
                items(prompts, key = { it.key }) { p -> PromptCard(data, now, p, ::handled) { logFor = p } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { later() }) { Text("Later (${data.settings.checkInLaterMin} min)") }
                        TextButton(onClick = onClose) { Text("Done") }
                    }
                    if (data.checkIn.laters >= 2) Text("Putting it off a third time adds a ladder point.", style = MaterialTheme.typography.bodySmall, color = Palette.amber)
                }
            }
        }
    }
    logFor?.let { p ->
        LogTimeDialog(data, now, p.blockStart, p.blockEnd, p.taskId, "What did you do instead?",
            onSaved = { UnlockCheckIn.handled(it, p.key, Graph.now()) }) { logFor = null }
    }
}

@Composable
private fun PromptCard(data: AppData, now: Long, p: UnlockCheckIn.Prompt, handled: (String) -> Unit, onLog: () -> Unit) {
    val ctx = LocalContext.current
    val zone = Time.zone(data.settings.zoneId)
    Panel(highlight = p.kind == UnlockCheckIn.Kind.PRIORITY) {
        Text(p.title, style = MaterialTheme.typography.titleMedium, color = if (p.kind == UnlockCheckIn.Kind.PRIORITY) Palette.red else MaterialTheme.colorScheme.onSurface)
        Text(p.text, style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
        when (p.kind) {
            UnlockCheckIn.Kind.ROUTINE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.openPkg?.let { pkg ->
                    val launch = remember(pkg) { ctx.packageManager.getLaunchIntentForPackage(pkg) }
                    if (launch != null) Button(onClick = {
                        Graph.update { Actions.markOpened(it, p.itemId!!, Graph.now()) }
                        runCatching { ctx.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) { Text("Open it") }
                }
                OutlinedButton(onClick = { Graph.update { UnlockCheckIn.handled(Actions.checkRoutine(it, p.itemId!!, Graph.now()), p.key, Graph.now()) } }) { Text("Did it") }
                var earlier by remember { mutableStateOf(false) }
                TextButton(onClick = { earlier = true }) { Text("Did it earlier") }
                TextButton(onClick = { handled(p.key) }) { Text("Not today") }
                if (earlier) EarlierDialog(now, zone, onDismiss = { earlier = false }) { at ->
                    Graph.update { UnlockCheckIn.handled(Actions.checkRoutine(it, p.itemId!!, at), p.key, Graph.now()) }
                }
            }
            UnlockCheckIn.Kind.WEIGH -> {
                var lb by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(lb, { lb = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("lb") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                    Button(enabled = lb.toDoubleOrNull()?.let { it in 60.0..600.0 } == true, onClick = {
                        if (DietSync.pushWeigh(ctx, lb.toDouble())) { handled(p.key); Thread { DietSync.sync(ctx) }.start() }
                    }) { Text("Save") }
                }
                TextButton(onClick = { handled(p.key) }) { Text("Skip today") }
            }
            UnlockCheckIn.Kind.MEAL -> {
                var text by remember { mutableStateOf("") }
                var ago by remember { mutableStateOf(0) }
                val slot = p.slot ?: p.title
                fun save(usual: Boolean) {
                    if (DietSync.pushQuickMeal(ctx, slot, text, Graph.now() - ago * Time.MIN, usual)) {
                        Graph.update { d -> UnlockCheckIn.handled(p.itemId?.let { Actions.checkRoutine(d, it, Graph.now() - ago * Time.MIN) } ?: d, p.key, Graph.now()) }
                        Thread { DietSync.sync(ctx) }.start()
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((m, label) in listOf(0 to "just now", 30 to "30 min ago", 60 to "1 h ago", 120 to "2 h ago", 180 to "3 h ago"))
                        FilterChip(ago == m, { ago = m }, { Text(label) })
                }
                p.usual?.let { u -> Button(onClick = { save(true) }) { Text("The usual: $u", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(text, { text = it }, placeholder = { Text("What was it? (fill in details in Twig)") }, modifier = Modifier.weight(1f))
                    OutlinedButton(enabled = text.isNotBlank(), onClick = { save(false) }) { Text("Save") }
                }
                Row {
                    TextButton(onClick = { DietSync.open(ctx) }) { Text("Open Twig") }
                    TextButton(onClick = { handled(p.key) }) { Text("Haven't eaten") }
                }
            }
            UnlockCheckIn.Kind.BLOCK -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onLog) { Text("I worked on…") }
                var why by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { why = true }) { Text("Something else") }
                TextButton(onClick = { Graph.update { UnlockCheckIn.excuse(it, p.blockStart, p.blockEnd, "nothing", false, Graph.now()) } }) { Text("Nothing, honestly") }
                if (why) ReasonDialog(onDismiss = { why = false }) { r ->
                    Graph.update { UnlockCheckIn.excuse(it, p.blockStart, p.blockEnd, r, true, Graph.now()) }
                }
            }
            UnlockCheckIn.Kind.PRIORITY -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { Graph.update { UnlockCheckIn.handled(Actions.startSession(it, p.taskId, null, Graph.now()), p.key, Graph.now()) }; Graph.refresh(ctx) }) { Text("Start it now") }
                OutlinedButton(onClick = onLog) { Text("I already did some") }
                TextButton(onClick = { handled(p.key) }) { Text("It's planned later") }
            }
        }
    }
}

@Composable
private fun EarlierDialog(now: Long, zone: java.time.ZoneId, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var at by remember { mutableLongStateOf(Time.roundUp(now - 60 * Time.MIN, 5)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("When?") },
        text = { TimeNudge("At", at, zone) { at = it.coerceIn(Time.at(Time.dateOf(now, zone), 0, zone), now) } },
        confirmButton = { TextButton(onClick = { onPick(at); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReasonDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var r by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What happened instead?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (s in listOf("class ran late", "helped someone", "errand", "rest I needed", "sick")) FilterChip(r == s, { r = s }, { Text(s) })
                }
                OutlinedTextField(r, { r = it }, placeholder = { Text("Say it plainly") })
                Text("Explained blocks don't count as missed. Only for things that weren't a choice to slack.", style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
            }
        },
        confirmButton = { TextButton(enabled = r.isNotBlank(), onClick = { onSave(r); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
