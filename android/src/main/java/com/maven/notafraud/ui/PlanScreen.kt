package com.maven.notafraud.ui

import androidx.compose.foundation.background
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
    val date = today.plusDays(offset.toLong())
    val plan = remember(data, offset, now / 60_000) {
        if (offset == 0) Planning.ensureToday(data, now).todayPlan!! else Planning.preview(data, date)
    }
    val current = if (offset == 0) NowResolver.pickCurrent(plan.blocks, now) else null

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0..2) {
                    val d = today.plusDays(i.toLong())
                    FilterChip(
                        selected = offset == i,
                        onClick = { offset = i },
                        label = { Text(if (i == 0) "Today" else d.format(DateTimeFormatter.ofPattern("EEE", Locale.US))) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (offset == 0) TextButton(onClick = { Graph.replan(); Graph.refresh(ctx) }) { Text("Replan") }
            }
            if (plan.note.isNotBlank()) Text(plan.note, color = Palette.amber, style = MaterialTheme.typography.bodySmall)
            if (offset > 0) Text("Preview. Tasks get placed for real on the morning of.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
        }
        items(plan.blocks, key = { "${it.start}-${it.kind}-${it.title}" }) { b ->
            BlockRow(
                b, zone,
                isCurrent = b == current,
                isPast = offset == 0 && b.end <= now,
                onStart = b.taskId?.takeIf { offset == 0 }?.let { id -> { Graph.update { Actions.startSession(it, id, null, Graph.now()) }; Graph.refresh(ctx) } },
            )
        }
        if (plan.blocks.isEmpty()) item { Text("Nothing planned.", color = Palette.textDim) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

fun blockColor(b: PlanBlock): Color = when {
    b.protected -> Palette.amber
    b.hot -> Palette.red
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
private fun BlockRow(b: PlanBlock, zone: java.time.ZoneId, isCurrent: Boolean, isPast: Boolean, onStart: (() -> Unit)?) {
    val alpha = if (isPast) 0.45f else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
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
        if (onStart != null && !isPast) TextButton(onClick = onStart) { Text("Start") }
    }
}
