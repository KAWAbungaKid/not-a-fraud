package com.maven.notafraud.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.AnchorKind
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.plan.BlockKind
import com.maven.notafraud.core.plan.NowResolver
import com.maven.notafraud.core.plan.PlanBlock
import com.maven.notafraud.core.plan.Planning
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.store.Actions
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PlanScreen(data: AppData, now: Long) {
    val ctx = LocalContext.current
    val zone = Time.zone(data.settings.zoneId)
    val today = Time.dateOf(now, zone)
    var offset by rememberSaveable { mutableIntStateOf(0) }
    var acting by remember { mutableStateOf<PlanBlock?>(null) }
    val date = today.plusDays(offset.toLong())
    val plan = remember(data, offset, now / 60_000) {
        val withPlan = Planning.ensureToday(data, now)
        if (offset == 0) withPlan.todayPlan!! else Planning.preview(withPlan, date)
    }
    val current = if (offset == 0) NowResolver.pickCurrent(plan.blocks, now) else null

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            androidx.compose.foundation.lazy.LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(Planning.WEEK_DAYS + 1) { i ->
                    val d = today.plusDays(i.toLong())
                    FilterChip(
                        selected = offset == i,
                        onClick = { offset = i },
                        label = { Text(if (i == 0) "Today" else d.format(DateTimeFormatter.ofPattern("EEE d", Locale.US))) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (offset > 0) "Draft. It firms up the morning of, and shifts as things change." else "Tap a block for options.",
                    color = Palette.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                if (offset == 0) TextButton(onClick = { Graph.replan(); Graph.refresh(ctx) }) { Text("Replan") }
            }
            if (plan.note.isNotBlank()) Text(plan.note, color = Palette.amber, style = MaterialTheme.typography.bodySmall)
        }
        items(plan.blocks, key = { "${it.start}-${it.kind}-${it.title}-${it.stepId}" }) { b ->
            BlockRow(
                b, zone,
                isCurrent = b == current,
                isPast = offset == 0 && b.end <= now,
                onTap = { acting = b },
            )
        }
        if (plan.blocks.isEmpty()) item { Text("Nothing planned.", color = Palette.textDim) }
        item { Spacer(Modifier.height(24.dp)) }
    }

    acting?.let { b -> BlockActions(b, data, java.time.LocalDate.parse(plan.date), today, onDismiss = { acting = null }) }
}

/** What you can do with a block: start / done / later / not today / pick a day / something came up; skip a class (and make it up). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BlockActions(b: PlanBlock, d: AppData, date: java.time.LocalDate, today: java.time.LocalDate, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val zone = Time.zone(d.settings.zoneId)
    val task = d.tasks.firstOrNull { it.id == b.taskId }
    val anchor = d.anchors.firstOrNull { it.id == b.anchorId }
    var pickTime by remember { mutableStateOf(false) }
    var pickDay by remember { mutableStateOf(false) }
    fun done(fn: (AppData) -> AppData) { Graph.changeAndReplan(ctx) { fn(it) }; onDismiss() }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(b.title, style = MaterialTheme.typography.titleLarge)
            Text("${Time.clock(b.start, zone)}–${Time.clock(b.end, zone)} · ${date.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US))}", color = Palette.textDim)
            if (task != null) {
                if (date == today) {
                    TextButton(onClick = { Graph.update { Actions.startSession(it, task.id, null, Graph.now()) }; Graph.refresh(ctx); onDismiss() }) { Text("Start now") }
                }
                if (b.stepId != null) {
                    TextButton(onClick = { done { Actions.checkStep(it, task.id, b.stepId!!, true, Graph.now()) } }) { Text("This step is done") }
                    TextButton(onClick = { done { Actions.setDone(it, task.id, true, Graph.now()) } }) { Text("The whole thing is done") }
                } else {
                    TextButton(onClick = { done { Actions.setDone(it, task.id, true, Graph.now()) } }) { Text("Done") }
                }
                if (date == today) {
                    TextButton(onClick = { pickTime = true }) { Text("Later today…") }
                    TextButton(onClick = { done { Actions.notToday(it, task.id, Graph.now()) } }) {
                        val dueToday = task.dueAt?.let { Time.dateOf(it, zone) == today } == true
                        Text(if (dueToday) "Not today (it's due today!)" else "Not today", color = if (dueToday) Palette.amber else MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = { done { Actions.somethingCameUp(it, task.id, Graph.now()) } }) { Text("Something came up: re-plan from now") }
                }
                TextButton(onClick = { pickDay = true }) { Text("Pick a day…") }
                TextButton(onClick = { done { Actions.bump(it, task.id, Graph.now()) } }) { Text("Bump importance for a week") }
            } else if (anchor != null && !anchor.protected) {
                TextButton(onClick = { done { Actions.skipAnchor(it, anchor.id, date, makeUp = true, now = Graph.now()) } }) { Text("Skip it and make it up later") }
                TextButton(onClick = { done { Actions.skipAnchor(it, anchor.id, date, makeUp = false, now = Graph.now()) } }) { Text("Skip it (no make-up)") }
            } else {
                Text(
                    when (b.kind) {
                        BlockKind.EVENT -> "From your Google Calendar. Change it there."
                        BlockKind.BACKGROUND -> "Running on its own. The next step is planned for when it ends."
                        else -> "Fixed block."
                    },
                    color = Palette.textDim,
                )
            }
        }
    }

    if (pickTime) TimeDialog(Time.minuteOfDay(Graph.now(), zone) + 60, onDismiss = { pickTime = false }) { m ->
        val at = Time.at(today, m, zone)
        done { Actions.laterToday(it, task!!.id, at, Graph.now()) }
        pickTime = false
    }
    if (pickDay) {
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = today.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli())
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickDay = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utc ->
                        val day = java.time.Instant.ofEpochMilli(utc).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        done { Actions.pickDay(it, task!!.id, day, Graph.now()) }
                    }
                    pickDay = false
                }) { Text("Move it") }
            },
            dismissButton = { TextButton(onClick = { pickDay = false }) { Text("Cancel") } },
        ) { androidx.compose.material3.DatePicker(state) }
    }
}

fun blockColor(b: PlanBlock): Color = when {
    b.protected -> Palette.amber
    b.hot -> Palette.red
    b.kind == BlockKind.RECHARGE -> Color(0xFFB0E0FF)
    b.kind == BlockKind.BACKGROUND -> Palette.textDim
    b.kind == BlockKind.TASK -> Palette.green
    b.kind == BlockKind.BREAK -> Palette.textDim
    b.kind == BlockKind.EVENT -> Color(0xFF6EC8FF)
    b.anchorKind == AnchorKind.CLASS -> Color(0xFFB58CFF)
    b.anchorKind == AnchorKind.FITNESS -> Color(0xFFFF8C6E)
    b.anchorKind == AnchorKind.RESEARCH -> Color(0xFF6EFFD9)
    b.anchorKind == AnchorKind.CRAFT -> Color(0xFFFFE06E)
    else -> Palette.greenDim
}

@Composable
private fun BlockRow(b: PlanBlock, zone: java.time.ZoneId, isCurrent: Boolean, isPast: Boolean, onTap: () -> Unit) {
    val alpha = if (isPast) 0.45f else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else if (b.kind == BlockKind.BACKGROUND) Color.Transparent else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onTap)
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
            .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(5.dp).fillMaxHeight().background(blockColor(b).copy(alpha = alpha)))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).weight(1f)) {
            Text(
                "${Time.clock(b.start, zone)}–${Time.clock(b.end, zone)}  ·  ${Time.durationText(b.minutes)}",
                style = MaterialTheme.typography.labelSmall, color = Palette.textDim.copy(alpha = alpha),
            )
            Text((if (b.picked) "★ " else "") + b.title, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
            val tag = listOfNotNull(
                if (b.hot) "hot" else null,
                when (b.mode) {
                    com.maven.notafraud.core.model.AnchorMode.HABIT -> if (b.routineId != null) "routine" else "habit"
                    com.maven.notafraud.core.model.AnchorMode.CONTAINER -> "container"
                    else -> null
                },
            )
            if (tag.isNotEmpty()) Text(tag.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = if (b.hot) Palette.red else Palette.textDim.copy(alpha = alpha))
        }

    }
}
