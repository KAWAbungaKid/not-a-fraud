package com.maven.notafraud.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
    val ranked = remember(data, now / 60_000) { Scorer.rank(data.tasks, now, 60, withPlan.todayPlan!!.energy).take(5) }
    var idea by rememberSaveable { mutableStateOf("") }

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
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Credits", "${data.ledger.balanceMin.toInt()}m", Modifier.weight(1f))
                Stat("Sleep", dayCtx?.sleepHours?.let { "%.1fh".format(it) } ?: "—", Modifier.weight(1f))
                Stat("Focus", Time.durationText(focusToday), Modifier.weight(1f))
                Stat("Phone", usage?.minutesByApp?.values?.sum()?.let { Time.durationText(it) } ?: "—", Modifier.weight(1f))
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = rough,
                    onClick = { Graph.changeAndReplan(ctx) { Actions.setRough(it, today, !(dayCtx?.markedRough ?: false)) } },
                    label = { Text(if (rough) "Rough day: lighter plan" else "Rough day?") },
                )
                Spacer(Modifier.width(8.dp))
                withPlan.todayPlan?.note?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.amber, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        item { SectionLabel("Up next") }
        items(ranked, key = { it.first.id }) { (task, score) ->
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
        if (ranked.isEmpty()) item { Text("No open tasks. Add some in Tasks.", color = Palette.textDim) }

        item { SectionLabel("Capture an idea (waits for Friday)") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(idea, { idea = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Side project idea…") })
                TextButton(onClick = {
                    if (idea.isNotBlank()) { act { Actions.captureIdea(it, idea.trim(), Graph.now()) }; idea = "" }
                }) { Text("Save") }
            }
        }

        val top = usage?.minutesByApp?.entries?.sortedByDescending { it.value }?.take(6).orEmpty()
        if (top.isNotEmpty()) {
            item { SectionLabel("Phone screen time today") }
            items(top, key = { it.key }) { (pkg, mins) ->
                val blocked = pkg in data.rules.blocked
                Row(Modifier.fillMaxWidth()) {
                    Text(usage?.labels?.get(pkg) ?: pkg, Modifier.weight(1f), color = if (blocked) Palette.amber else Palette.text)
                    Text(Time.durationText(mins), style = Mono, color = Palette.textDim)
                }
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
