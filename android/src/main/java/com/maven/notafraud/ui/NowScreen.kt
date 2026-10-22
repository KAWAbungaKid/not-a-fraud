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
    var idea by rememberSaveable { mutableStateOf("") }

    val hidden = data.settings.nowHidden
    fun act(fn: (AppData) -> AppData) { Graph.update(fn); Graph.refresh(ctx) }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.padding(top = 8.dp))
            Text(today.format(DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)), style = MaterialTheme.typography.labelSmall, color = Palette.textDim)
            Text(data.settings.creed, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
        }

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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { act { Actions.startSession(it, t?.id, null, Graph.now()) } }) {
                            Text(if (t != null) "Start" else "Start session")
                        }
                        if (t != null) OutlinedButton(onClick = { Graph.changeAndReplan(ctx) { Actions.setDone(it, t.id, true, Graph.now()) } }) { Text("Done") }
                    }
                }
                state.routine?.let { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Routine: $r", color = Palette.amber, modifier = Modifier.weight(1f))
                        state.routineId?.let { id ->
                            TextButton(onClick = { act { Actions.checkRoutine(it, id, Graph.now()) } }) { Text("Done") }
                        }
                    }
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

        if ("idea" !in hidden) item { SectionLabel("Capture an idea (waits for Friday)") }
        if ("idea" !in hidden) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(idea, { idea = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Side project idea…") })
                TextButton(onClick = {
                    if (idea.isNotBlank()) { act { Actions.captureIdea(it, idea.trim(), Graph.now()) }; idea = "" }
                }) { Text("Save") }
            }
        }

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
