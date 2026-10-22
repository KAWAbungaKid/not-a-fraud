package com.maven.notafraud.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.Skill
import com.maven.notafraud.core.model.SkillTree
import com.maven.notafraud.core.plan.Practice
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.stats.Skills
import com.maven.notafraud.core.stats.Stat
import com.maven.notafraud.core.stats.Stats
import com.maven.notafraud.core.store.Actions

// ---------------------------------------------------------------- Stats

fun statColor(score: Double?): Color = when {
    score == null -> Palette.textDim
    score < Stats.LOW -> Palette.red
    score < 6.5 -> Palette.amber
    else -> Palette.green
}

@Composable
fun StatsScreen(data: AppData, now: Long) {
    val zone = Time.zone(data.settings.zoneId)
    val today = Time.dateOf(now, zone)
    val stats = remember(data, today) { Stats.all(data, today) }
    val pushed = remember(data, now / 3_600_000) { Stats.mostPushed(data, now) }
    var open by remember { mutableStateOf<String?>(null) }
    var checkingIn by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Last 7 days, 1–10", style = MaterialTheme.typography.titleLarge)
            Text("Only what the phone measures on its own: sleep, workouts, steps, meals, focus time, following the plan.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
            Text(
                if (data.settings.statsDriveImportance) "A stat under ${Stats.LOW.toInt()} makes its areas more important in the plan (a task's own importance still wins)."
                else "Stats are for looking only (Setup → Planning to let them steer the plan).",
                color = Palette.textDim, style = MaterialTheme.typography.bodySmall,
            )
        }
        items(stats, key = { it.key }) { st ->
            StatRow(st, expanded = open == st.key) { open = if (open == st.key) null else st.key }
        }
        if (data.settings.checkInEnabled) item {
            OutlinedButton(onClick = { checkingIn = true }) { Text("Weekly check-in") }
        }
        val usage = data.usage.firstOrNull { it.date == today.toString() && it.device == "phone" }
        val top = usage?.minutesByApp?.entries?.sortedByDescending { it.value }?.take(8).orEmpty()
        if (top.isNotEmpty()) {
            item { SectionLabel("Phone screen time today · " + Time.durationText(usage!!.minutesByApp.values.sum())) }
            items(top, key = { "u" + it.key }) { (pkg, mins) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(usage?.labels?.get(pkg) ?: pkg, Modifier.weight(1f), color = if (pkg in data.rules.blocked) Palette.amber else Palette.text)
                    Text(Time.durationText(mins), style = Mono, color = Palette.textDim)
                }
            }
        }
        if (pushed.isNotEmpty()) {
            item { SectionLabel("Pushed back most (14 days)") }
            items(pushed, key = { "p" + it.first.id }) { (t, n) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(t.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${n}×", style = Mono, color = Palette.textDim)
                }
            }
            item { Text("Just a pattern to notice. Nothing changes because of it.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (checkingIn) CheckInDialog(data, now) { checkingIn = false }
}

@Composable
private fun StatRow(st: Stat, expanded: Boolean, onClick: () -> Unit) {
    Panel(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(st.name, Modifier.width(96.dp), style = MaterialTheme.typography.titleMedium)
            Box(Modifier.weight(1f).height(12.dp).background(Palette.surfaceHigh, RoundedCornerShape(6.dp))) {
                val f = ((st.score ?: 0.0) / 10.0).toFloat().coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth(f).height(12.dp).background(statColor(st.score), RoundedCornerShape(6.dp)))
            }
            Spacer(Modifier.width(10.dp))
            Text(st.score?.let { "%.1f".format(it) } ?: "—", style = Mono, color = statColor(st.score))
            val tr = st.trend
            Text(
                when { tr == null -> "  "; tr > 0.3 -> " ▲"; tr < -0.3 -> " ▼"; else -> " ·" },
                color = when { tr == null -> Palette.textDim; tr > 0.3 -> Palette.green; tr < -0.3 -> Palette.red; else -> Palette.textDim },
            )
        }
        if (expanded) {
            for ((k, v) in st.parts) Row(Modifier.fillMaxWidth()) {
                Text(k, Modifier.weight(1f), color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                Text(v, style = MaterialTheme.typography.bodySmall)
            }
            st.prev?.let { Text("Week before: %.1f".format(it), color = Palette.textDim, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Sunday check-in: the stats the phone can't measure. */
@Composable
fun CheckInDialog(data: AppData, now: Long, onDismiss: () -> Unit) {
    val zone = Time.zone(data.settings.zoneId)
    val week = Practice.weekStart(Time.dateOf(now, zone))
    val existing = data.checkIns.firstOrNull { it.weekStart == week.toString() }?.ratings.orEmpty()
    val keys = listOf("social" to "Time with people", "spirit" to "Faith / spirit", "stress" to "Calm (5 = no stress)", "happy" to "Happiness")
    var r by remember { mutableStateOf(existing) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This week, 1–5") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((k, label) in keys) {
                    Text(label)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (v in 1..5) FilterChip(r[k] == v, { r = r + (k to v) }, { Text("$v") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Graph.update { Actions.checkIn(it, week, r, Graph.now()) }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } },
    )
}

