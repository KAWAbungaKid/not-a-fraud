package com.maven.notafraud.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.liveTasks
import com.maven.notafraud.core.plan.Generated
import com.maven.notafraud.core.plan.NowResolver
import com.maven.notafraud.core.plan.Planning
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.score.Scorer
import com.maven.notafraud.core.store.Actions
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun NowScreen(data: AppData, now: Long, onOpenTab: (String) -> Unit) {
    val ctx = LocalContext.current
    val zone = Time.zone(data.settings.zoneId)
    val today = Time.dateOf(now, zone)
    val withPlan = remember(data, now / 60_000) { Planning.ensureToday(data, now) }
    val state = remember(withPlan, now / 60_000) { NowResolver.resolve(withPlan.todayPlan!!, withPlan, now) }
    val dayCtx = data.days.firstOrNull { it.date == today.toString() }
    val rough = withPlan.todayPlan?.roughDay == true
    val usage = data.usage.firstOrNull { it.date == today.toString() && it.device == "phone" }
    val startToday = Time.at(today, 0, zone)
    val focusToday = data.sessions.filter { it.start >= startToday }.sumOf { it.minutes } +
        (data.active?.let { ((now - it.start) / Time.MIN).toInt() } ?: 0)
    val ranked = remember(data, now / 60_000) { Scorer.rank(data.liveTasks(), now, 60, withPlan.todayPlan!!.energy).take(5) }

    val guardOk = remember(now / 60_000) { com.maven.notafraud.guard.GuardHealth.state(ctx) == com.maven.notafraud.guard.GuardHealth.State.RUNNING }
    val hidden = data.settings.nowHidden
    fun act(fn: (AppData) -> AppData) { Graph.update(fn); Graph.refresh(ctx) }
    var freed by remember { mutableStateOf<String?>(null) }
    var freedTask by remember { mutableStateOf<String?>(null) }
    var logWhat by remember { mutableStateOf<Triple<Long, String?, String>?>(null) }
    var showCheckIn by remember { mutableStateOf(false) }
    val checkIns = remember(data, now / 60_000) { com.maven.notafraud.core.guard.UnlockCheckIn.prompts(withPlan, now) }
    logWhat?.let { (start, task, heading) -> LogTimeDialog(withPlan, now, start, now, task, heading) { logWhat = null } }
    if (showCheckIn) CheckInSheet(withPlan, now) { showCheckIn = false }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.padding(top = 8.dp))
            Text(today.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)), style = MaterialTheme.typography.labelSmall, color = Palette.textDim)
            Text(data.settings.creed, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
        }

        // Blocking switched on but not actually running: say so at the top, with the fix.
        if (data.rules.blockingEnabled && !guardOk) item { GuardStatusPanel() }

        item {
            Panel(highlight = true) {
                SectionLabel(if (state.working) "Working" else "Right now")
                Text(state.headline, style = MaterialTheme.typography.headlineSmall)
                if (state.detail.isNotBlank()) Text(state.detail, color = Palette.textDim)
                val active = data.active
                if (active != null) {
                    val mins = ((now - active.start) / Time.MIN).toInt()
                    Text(Time.durationText(mins), style = MaterialTheme.typography.displaySmall, color = Palette.green)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { act { Actions.stopSession(it, Graph.now()) } }) { Text("Stop") }
                        active.taskId?.let { id ->
                            OutlinedButton(onClick = { Graph.changeAndReplan(ctx) { Actions.setDone(it, id, true, Graph.now()) } }) { Text("Done") }
                        }
                    }
                } else if (!state.protected) {
                    val t = state.suggested
                    val cur = state.current
                    fun short(x: String) = x.substringBefore(" [").substringBefore(" - ").take(28).trim()
                    when {
                        cur != null && cur.kind == com.maven.notafraud.core.plan.BlockKind.TASK -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { act { Actions.startSession(it, t?.id, cur.title, Graph.now()) } }) { Text("Start working") }
                            if (t != null) OutlinedButton(onClick = { Graph.changeAndReplan(ctx) { Actions.setDone(it, t.id, true, Graph.now()) } }) { Text("Finished it") }
                        }
                        cur != null && cur.routineId == null && (cur.kind == com.maven.notafraud.core.plan.BlockKind.ANCHOR || cur.kind == com.maven.notafraud.core.plan.BlockKind.EVENT) -> {
                            // A class, lab or trading: say it's over early and the plan fills the time.
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val left = ((cur.end - Graph.now()) / Time.MIN).toInt()
                                    Graph.changeAndReplan(ctx) { Actions.endBlockEarly(it, com.maven.notafraud.core.plan.DayPlanner.endKey(cur), Graph.now()) }
                                    val fill = com.maven.notafraud.core.plan.DayReview.fill(Graph.data, Graph.now(), left)
                                    freed = "${short(cur.title)} ended ${Time.durationText(left)} early." + (fill?.let { " Fill it with: ${it.title}" } ?: "")
                                    freedTask = fill?.id
                                }) { Text("${short(cur.title)} is done") }
                                if (t != null) TextButton(onClick = { act { Actions.startSession(it, t.id, null, Graph.now()) } }) { Text("Work on ${short(t.title)}") }
                            }
                        }
                        cur != null -> {}
                        t != null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { act { Actions.startSession(it, t.id, null, Graph.now()) } }) { Text("Start ${short(t.title)}") }
                            OutlinedButton(onClick = { Graph.changeAndReplan(ctx) { Actions.setDone(it, t.id, true, Graph.now()) } }) { Text("Already done") }
                        }
                        else -> Button(onClick = { act { Actions.startSession(it, null, null, Graph.now()) } }) { Text("Start a session") }
                    }
                }
                freed?.let { f ->
                    Text(f, color = Palette.green, style = MaterialTheme.typography.bodyMedium)
                    freedTask?.let { id ->
                        Row {
                            Button(onClick = { act { Actions.startSession(it, id, null, Graph.now()) }; freed = null }) { Text("Start it") }
                            TextButton(onClick = { freed = null }) { Text("OK") }
                        }
                    }
                }
                // Backlog: worked off the phone, or kept going past the block.
                val prev = withPlan.todayPlan?.blocks.orEmpty()
                    .filter { it.end <= now && it.end > now - 3 * Time.HOUR && (it.kind == com.maven.notafraud.core.plan.BlockKind.TASK || it.anchorKind == com.maven.notafraud.core.model.AnchorKind.MARKET) }
                    .maxByOrNull { it.end }
                Row {
                    if (data.active == null && prev != null && (state.current == null || state.current?.start?.let { it >= prev.end } == true))
                        TextButton(onClick = { logWhat = Triple(prev.end, prev.taskId, "Went over on ${prev.title.substringBefore(" [")}?") }) { Text("I went over") }
                    TextButton(onClick = { logWhat = Triple(now - 30 * Time.MIN, null, "What did you work on?") }) { Text("I worked on…") }
                }
                state.routine?.let { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Routine overdue: $r", color = Palette.amber, modifier = Modifier.weight(1f))
                        state.routineId?.let { id ->
                            TextButton(onClick = { act { Actions.checkRoutine(it, id, Graph.now()) } }) { Text("✓ Did it") }
                        }
                    }
                }
            }
        }
        if (checkIns.isNotEmpty()) item {
            Panel(Modifier.clickable { showCheckIn = true }) {
                Text("Check in · ${checkIns.size} thing${if (checkIns.size == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium, color = Palette.amber)
                Text(checkIns.take(3).joinToString(" · ") { it.title }, style = MaterialTheme.typography.bodySmall, color = Palette.textDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        item { SummaryCard(data, now) }
        if ("score" !in hidden) item { DayScoreCard(data, now) { logWhat = Triple(now - 60 * Time.MIN, com.maven.notafraud.core.plan.DayReview.priorityNag(data, now)?.task?.id, "Log time on it") } }
        item { SqueezeCard(withPlan, now) }

        val wk = com.maven.notafraud.core.workout.WorkoutLink.today(data, now)
        if (wk != null && "workout" !in hidden) item {
            val must = com.maven.notafraud.core.workout.WorkoutLink.mustToday(data, now)
            val tight = com.maven.notafraud.core.workout.WorkoutLink.tight(data, now)
            val col = when { must -> Palette.red; tight -> Palette.amber; wk.risk == com.maven.notafraud.core.workout.WorkoutLink.MISSED -> Palette.textDim; else -> Palette.green }
            Panel(highlight = must) {
                SectionLabel("Workouts · ${wk.doneThisWeek} of ${wk.minPerWeek} minimum (${wk.targetPerWeek} planned)")
                Text(
                    when {
                        wk.inProgress -> "In progress: ${wk.inProgressName ?: "workout"}"
                        wk.trainedToday -> "Done today."
                        must -> "Today has to be a workout."
                        tight -> "One spare day left this week."
                        else -> wk.message
                    },
                    style = MaterialTheme.typography.titleMedium, color = col,
                )
                if (!wk.trainedToday && !wk.inProgress) {
                    Text(wk.message, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                    if (must && data.settings.workoutPausesCredits) Text("Credits are paused until it's started.", color = Palette.red, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { com.maven.notafraud.sense.WorkoutSync.open(ctx) }) { Text("Start workout" + (wk.suggestedToday?.let { ": $it" } ?: "")) }
                }
                if (wk.injured.isNotEmpty()) Text("Injured: " + wk.injured.joinToString { it.lowercase().replace('_', ' ') }, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
            }
        }

        val ready = com.maven.notafraud.core.store.Lock.due(data, now)
        if (ready.isNotEmpty()) item {
            Panel(highlight = true) {
                SectionLabel("You asked for these yesterday. Still want them?")
                for (c in ready) {
                    Text(c.label, style = MaterialTheme.typography.titleSmall)
                    c.reason?.let { Text("You said: \"$it\"", color = Palette.amber, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            // A deleted meal that Not a Twig owns goes from Twig's schedule too.
                            if (c.kind == com.maven.notafraud.core.model.ChangeKind.ROUTINE_REMOVE && c.flag == true) data.routine.firstOrNull { it.id == c.pkg }?.dietSlot?.let { slot ->
                                if (data.diet != null) com.maven.notafraud.sense.DietSync.pushMeal(ctx, slot, null, null, remove = true)
                            }
                            act { com.maven.notafraud.core.store.Lock.confirm(it, c.id) }
                        }) { Text("Yes, change it") }
                        Button(onClick = { act { com.maven.notafraud.core.store.Lock.discard(it, c.id) } }) { Text("No, keep it") }
                    }
                }
            }
        }

        if ("homework" !in hidden) {
            val ahead = com.maven.notafraud.core.plan.Homework.ahead(data, now)
            if (ahead.openCount > 0) item {
                Panel {
                    SectionLabel("Homework")
                    val d = ahead.days
                    Text(
                        when {
                            d == null -> "Nothing due in the next 2 weeks"
                            d < 0 -> "Overdue: ${ahead.next?.title}"
                            else -> "Next unfinished is due in " + (if (d < 1) "${(d * 24).toInt()} h" else "%.1f days".format(d)) + " · goal: ${ahead.goalDays} days ahead"
                        },
                        style = MaterialTheme.typography.titleSmall, color = if (ahead.onTrack) Palette.green else Palette.amber,
                    )
                    if (!ahead.onTrack) Text(
                        "To get ${ahead.goalDays} days ahead: ${Time.durationText(ahead.behindMin)} of homework due before " +
                            Time.dateOf(now + ahead.goalDays * Time.DAY, zone).format(DateTimeFormatter.ofPattern("EEE", Locale.US)) +
                            " is left. The plan works it in first; finishing early is what builds the cushion.",
                        color = Palette.textDim, style = MaterialTheme.typography.bodySmall,
                    )
                    else Text("You're ahead. The plan keeps working ${ahead.goalDays} days out so it stays that way.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                    ahead.next?.let { t -> Text("Next: ${t.title} · ${Time.durationText(com.maven.notafraud.core.score.Scorer.minutesLeft(t))} left", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        if (data.reviewTaskIds.isNotEmpty()) item {
            var open by remember { mutableStateOf(false) }
            Panel(Modifier.clickable { open = true }, highlight = true) {
                Text("${data.reviewTaskIds.size} old tasks to review", style = MaterialTheme.typography.titleMedium)
                Text("From the old web app. Keep, done, or delete: one tap each.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
            }
            if (open) ReviewDialog(data, now) { open = false }
        }

        if ("pick3" !in hidden) item { PickThree(data, now) }

        if ("numbers" !in hidden) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Credits", "${data.ledger.balanceMin.toInt()}m", Modifier.weight(1f))
                val napMin = dayCtx?.naps?.sumOf { it.minutes } ?: 0
                Stat("Sleep", (dayCtx?.sleepHours?.let { "%.1fh".format(it) } ?: "—") + if (napMin > 0) " +${napMin}m" else "", Modifier.weight(1f))
                Stat("Focus", Time.durationText(focusToday), Modifier.weight(1f))
                Stat("Phone", usage?.minutesByApp?.values?.sum()?.let { Time.durationText(it) } ?: "—", Modifier.weight(1f))
            }
        }

        val plan = withPlan.todayPlan
        if ("moved" !in hidden && plan != null && (plan.dropped.isNotEmpty() || plan.atRisk.isNotEmpty())) item {
            Panel {
                if (plan.dropped.isNotEmpty()) {
                    SectionLabel("Moved off today")
                    Text(plan.dropped.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Text("A block slipped by, so the rest of the day was re-planned. These are in the coming days.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                }
                if (plan.atRisk.isNotEmpty()) {
                    SectionLabel("Won't fit this week")
                    Text(plan.atRisk.joinToString(" · "), color = Palette.amber, style = MaterialTheme.typography.bodySmall)
                    Text("Due within 7 days, more work than the week has room for. Cut scope, ask for time, or bump it.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        val week = com.maven.notafraud.core.plan.Practice.weekStart(today)
        val checkedIn = data.checkIns.any { it.weekStart == week.toString() }
        if (data.settings.checkInEnabled && !checkedIn && (today.dayOfWeek == java.time.DayOfWeek.SUNDAY || today.dayOfWeek == java.time.DayOfWeek.SATURDAY)) item {
            var open by remember { mutableStateOf(false) }
            Panel(Modifier.clickable { open = true }) {
                Text("Weekly check-in", style = MaterialTheme.typography.titleMedium)
                Text("Four taps: people, faith, calm, happiness. Feeds your stats.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
            }
            if (open) CheckInDialog(data, now) { open = false }
        }

        if ("today" !in hidden || Graph.lowPromptPending) item {
            var asking by remember { mutableStateOf(Graph.lowPromptPending.also { Graph.lowPromptPending = false }) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val mode = dayCtx?.mode
                FilterChip(
                    selected = mode != null || rough,
                    onClick = { asking = true },
                    label = { Text(mode?.let { "Today: " + modeName(it) } ?: if (rough) "Rough day · How's today?" else "How's today?") },
                )
                Spacer(Modifier.width(8.dp))
                withPlan.todayPlan?.note?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.amber, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (asking) HowsToday(data, now) { asking = false }
        }

        if ("upnext" !in hidden) item { SectionLabel("Up next") }
        if ("upnext" !in hidden) items(ranked, key = { it.first.id }) { (task, score) ->
            val project = data.projects.firstOrNull { it.id == task.projectId }?.name
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(task.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(project, "${Scorer.minutesLeft(task)}m left", task.dueAt?.let { "due " + dueText(it, now, zone) }, "score ${score.toInt()}")
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = Palette.textDim,
                        )
                    }
                    TextButton(onClick = { act { Actions.startSession(it, task.id, null, Graph.now()) } }) { Text("Start") }
                }
            }
        }
        if ("upnext" !in hidden && ranked.isEmpty()) item { Text("No open tasks. Add some in Tasks.", color = Palette.textDim) }

        item { Spacer(Modifier.padding(bottom = 24.dp)) }
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.textDim)
        Text(value, style = MaterialTheme.typography.titleMedium.merge(Mono), color = Palette.green, maxLines = 1)
    }
}

fun dueText(due: Long, now: Long, zone: java.time.ZoneId): String {
    val hours = (due - now) / Time.HOUR.toDouble()
    return when {
        hours < 0 -> "overdue"
        hours < 24 -> "in ${hours.toInt()}h"
        else -> Time.dateOf(due, zone).format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
    }
}


/**
 * Evening (from 5pm): pick tomorrow's 3. Daytime: show today's 3.
 * Picked tasks are planned first and starred.
 */
@Composable
fun PickThree(data: AppData, now: Long) {
    val ctx = LocalContext.current
    val zone = Time.zone(data.settings.zoneId)
    val today = Time.dateOf(now, zone)
    val evening = Time.minuteOfDay(now, zone) >= 17 * 60
    val target = if (evening) today.plusDays(1) else today
    val picked = data.picks[target.toString()].orEmpty()
    var choosing by remember { mutableStateOf(false) }
    val titles = picked.mapNotNull { id -> data.tasks.firstOrNull { it.id == id } }

    if (!evening && titles.isEmpty()) return
    Panel(highlight = evening && titles.isEmpty()) {
        SectionLabel(if (evening) "Tomorrow's 3" else "Today's 3")
        if (titles.isEmpty()) Text("Pick the three things that matter most tomorrow. The plan protects them.", color = Palette.textDim)
        titles.forEach { t ->
            Text("★ " + t.title, textDecoration = if (t.status == com.maven.notafraud.core.model.TaskStatus.DONE) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
        }
        TextButton(onClick = { choosing = true }) { Text(if (titles.isEmpty()) "Pick 3" else "Change") }
    }

    if (choosing) {
        var chosen by remember { mutableStateOf(picked) }
        val options = Scorer.rank(data.liveTasks(), Time.at(target, 8 * 60, zone), 120, data.settings.energy).map { it.first }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Pick 3 for ${target.format(DateTimeFormatter.ofPattern("EEEE", Locale.US))}") },
            confirmButton = {
                TextButton(onClick = {
                    Graph.update { Actions.setPicks(it, target, chosen, Graph.now()) }
                    if (target == today) Graph.replan()
                    Graph.refresh(ctx); choosing = false
                }) { Text("Save (${chosen.size}/3)") }
            },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
            text = {
                LazyColumn(Modifier.androidx_heightMax()) {
                    items(options, key = { it.id }) { t ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                chosen = if (t.id in chosen) chosen - t.id else if (chosen.size < 3) chosen + t.id else chosen
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(checked = t.id in chosen, onCheckedChange = null)
                            Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
        )
    }
}

private fun Modifier.androidx_heightMax() = this.then(Modifier.heightIn(max = 420.dp))


fun modeName(m: com.maven.notafraud.core.model.DayMode) = when (m) {
    com.maven.notafraud.core.model.DayMode.PUSH -> "push"
    com.maven.notafraud.core.model.DayMode.RECHARGE -> "recharge, then push"
    com.maven.notafraud.core.model.DayMode.RESET -> "reset"
    com.maven.notafraud.core.model.DayMode.WRITEOFF -> "written off"
}

/** "How's today?" — push / recharge then push / reset / write it off, with a suggestion. */
@Composable
fun HowsToday(data: AppData, now: Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val zone = Time.zone(data.settings.zoneId)
    val today = Time.dateOf(now, zone)
    val advice = remember { com.maven.notafraud.core.plan.DayAdvisor.suggest(data, now) }
    var nap by remember { mutableStateOf(if (advice.mode == com.maven.notafraud.core.model.DayMode.RECHARGE) advice.rechargeMin else 20) }
    fun choose(m: com.maven.notafraud.core.model.DayMode?) {
        Graph.changeAndReplan(ctx) { Actions.setDayMode(it, today, m, nap, Graph.now()) }
        onDismiss()
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How's today?") },
        confirmButton = { TextButton(onClick = { choose(null) }) { Text("Normal day") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Panel(highlight = true) {
                    SectionLabel("Suggestion: " + modeName(advice.mode))
                    Text(advice.reason)
                }
                com.maven.notafraud.core.diet.DietLink.today(data, now)?.let { s ->
                    SectionLabel("Not a Twig")
                    Text(
                        com.maven.notafraud.core.diet.DietLink.summary(s) + if (s.clarity == null) " · no check-in yet" else "",
                        color = if (com.maven.notafraud.core.diet.DietLink.lowCheckIn(s)) Palette.amber else Palette.textDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val signals = remember { com.maven.notafraud.core.stats.Burnout.signals(data, now) }
                if (signals.isNotEmpty()) {
                    SectionLabel("Signs of running low")
                    for (sg in signals) Text("· $sg", color = Palette.amber, style = MaterialTheme.typography.bodySmall)
                }
                ModeOption("Push", "Keep the plan. 25-minute blocks with 5-minute breaks, easiest win first.", advice.mode == com.maven.notafraud.core.model.DayMode.PUSH) { choose(com.maven.notafraud.core.model.DayMode.PUSH) }
                ModeOption("Recharge, then push", "Nap or walk first, then a lighter plan (50-minute blocks, 4 h focus cap).", advice.mode == com.maven.notafraud.core.model.DayMode.RECHARGE) { choose(com.maven.notafraud.core.model.DayMode.RECHARGE) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (m in listOf(20, 45, 90)) FilterChip(nap == m, { nap = m }, { Text(if (m == 90) "90 nap" else "${m}m") })
                }
                ModeOption("Reset", "Only what's due soon, your picks and easy stuff, plus a recharge block. Everything else moves to the coming days.", advice.mode == com.maven.notafraud.core.model.DayMode.RESET) { choose(com.maven.notafraud.core.model.DayMode.RESET) }
                ModeOption("Write it off", "Fixed commitments only (classes, exams, trading, events). Rest on purpose.", advice.mode == com.maven.notafraud.core.model.DayMode.WRITEOFF) { choose(com.maven.notafraud.core.model.DayMode.WRITEOFF) }
            }
        },
    )
}

@Composable
private fun ModeOption(title: String, text: String, suggested: Boolean, onClick: () -> Unit) {
    Panel(Modifier.clickable(onClick = onClick)) {
        Text(title + if (suggested) "  ·  suggested" else "", style = MaterialTheme.typography.titleMedium, color = if (suggested) Palette.green else MaterialTheme.colorScheme.onSurface)
        Text(text, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
    }
}

/** Old web-app tasks: keep / done / delete, one tap each. */
@Composable
fun ReviewDialog(data: AppData, now: Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val zone = Time.zone(data.settings.zoneId)
    val list = data.tasks.filter { it.id in data.reviewTaskIds }.sortedBy { it.dueAt ?: Long.MAX_VALUE }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review old tasks (${list.size})") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.id }) { t ->
                    Panel {
                        Text(t.title)
                        Text(
                            listOfNotNull(data.projects.firstOrNull { it.id == t.projectId }?.name, t.dueAt?.let { "due " + dueText(it, now, zone) }).joinToString(" · "),
                            color = Palette.textDim, style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            TextButton(onClick = { Graph.update { Actions.reviewKeep(it, t.id) } }) { Text("Keep") }
                            TextButton(onClick = { Graph.changeAndReplan(ctx) { Actions.reviewDone(it, t.id, Graph.now()) } }) { Text("Done") }
                            TextButton(onClick = { Graph.changeAndReplan(ctx) { Actions.deleteTask(it, t.id) } }) { Text("Delete", color = Palette.red) }
                        }
                    }
                }
            }
        },
    )
}
