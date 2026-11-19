@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.maven.notafraud.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.maven.notafraud.Graph
import com.maven.notafraud.core.guard.Credits
import com.maven.notafraud.core.guard.Pauses
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.PauseCover
import com.maven.notafraud.core.plan.Time
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A text toolbar with no Paste (or anything else): the phrase has to be typed. */
private object NoToolbar : TextToolbar {
    override val status: TextToolbarStatus get() = TextToolbarStatus.Hidden
    override fun hide() {}
    override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?, onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {}
}

private val WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)

/** Active / upcoming planned unlocks, with End now. Shown on Now and the Brick ladder page. */
@Composable
fun PauseBanner(data: AppData, now: Long, showSetUp: Boolean = false) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val zone = Time.zone(data.settings.zoneId)
    val list = Pauses.current(data, now)
    var open by remember { mutableStateOf(false) }
    if (list.isEmpty() && !showSetUp) return
    Panel(highlight = list.any { it.start <= now }) {
        SectionLabel("Planned unlock")
        for (p in list) {
            Text(Pauses.text(p, zone, now), color = Palette.amber)
            Text("Why: ${p.reason}", style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
            TextButton(onClick = { Graph.update { Pauses.end(it, p.id, Graph.now()) }; Graph.refresh(ctx) }) {
                Text(if (p.start > now) "Cancel it" else "End it now")
            }
        }
        if (showSetUp) {
            if (list.isEmpty()) Text(
                "Switch off the brick, blocked apps, the check-in or nags from a time to a time (to the minute). Setting one up means typing a long phrase, no pasting. Ending early is instant.",
                style = MaterialTheme.typography.bodySmall, color = Palette.textDim,
            )
            OutlinedButton(onClick = { open = true }) { Text(if (list.isEmpty()) "Set up a planned unlock" else "Set up another") }
        }
    }
    if (open) PauseDialog(data, now) { open = false }
}

@Composable
fun PauseDialog(data: AppData, now: Long, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val zone = Time.zone(data.settings.zoneId)
    var startNow by remember { mutableStateOf(true) }
    var start by remember { mutableLongStateOf(now) }
    var end by remember { mutableLongStateOf(Time.at(Time.dateOf(now, zone).plusDays(1), 17 * 60, zone)) }
    var covers by remember { mutableStateOf(setOf(PauseCover.BRICK, PauseCover.CHECKIN)) }
    var reason by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) }   // "start" / "end"
    var error by remember { mutableStateOf<String?>(null) }
    val phrase = data.settings.pausePhrase

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Palette.bg) {
            LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("Planned unlock", style = MaterialTheme.typography.headlineSmall)
                    Text("For trips, travel days, setting up new apps. Calls and emergencies already work without this.", style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
                }
                item {
                    SectionLabel("When")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(startNow, { startNow = it })
                        Text("Starting now", Modifier.clickable { startNow = !startNow })
                    }
                    if (!startNow) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("From " + Instant.ofEpochMilli(start).atZone(zone).format(WHEN), Modifier.weight(1f))
                        TextButton(onClick = { picking = "start" }) { Text("Change") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Until " + Instant.ofEpochMilli(end).atZone(zone).format(WHEN), Modifier.weight(1f), color = Palette.green)
                        TextButton(onClick = { picking = "end" }) { Text("Change") }
                    }
                    val s = if (startNow) now else start
                    if (end > s) Text("That's " + Time.durationText(((end - s) / Time.MIN).toInt()), style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
                }
                item {
                    SectionLabel("Switch off")
                    for (c in PauseCover.entries) Row(Modifier.fillMaxWidth().clickable { covers = if (c in covers) covers - c else covers + c }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(c in covers, { covers = if (it) covers + c else covers - c })
                        Column {
                            Text(c.label)
                            Text(c.blurb, style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
                        }
                    }
                }
                item {
                    OutlinedTextField(reason, { reason = it }, label = { Text("Why (you'll see this the whole time)") }, modifier = Modifier.fillMaxWidth())
                }
                item {
                    SectionLabel("Type this out")
                    Text("\"$phrase\"", color = Palette.textDim)
                    // No paste: the toolbar is gone, and anything that jumps more than a few letters at once is ignored.
                    CompositionLocalProvider(LocalTextToolbar provides NoToolbar) {
                        OutlinedTextField(
                            typed,
                            { v -> if (v.length <= typed.length + 3) typed = v },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                            placeholder = { Text("Type it, word for word") },
                        )
                    }
                    val ok = Credits.phraseMatches(typed, phrase)
                    Text(if (ok) "Matches." else "${typed.length} / ${phrase.length}", style = MaterialTheme.typography.bodySmall, color = if (ok) Palette.green else Palette.textDim)
                }
                item {
                    error?.let { Text(it, color = Palette.red) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val n = Graph.now()
                            when (val r = Pauses.create(Graph.data, if (startNow) n else start, end, covers, reason, typed, n)) {
                                is Pauses.Result.No -> error = r.why
                                is Pauses.Result.Ok -> { Graph.update { r.data }; Graph.refresh(ctx); onDismiss() }
                            }
                        }, enabled = Credits.phraseMatches(typed, phrase) && covers.isNotEmpty()) { Text("Unlock") }
                        TextButton(onClick = onDismiss) { Text("Never mind") }
                    }
                }
            }
        }
    }

    picking?.let { which ->
        val initial = if (which == "start") start else end
        DateTimePick(initial, zone, onDismiss = { picking = null }) { t ->
            if (which == "start") start = t else end = t
            picking = null
        }
    }
}

/** Date, then time (to the minute). */
@Composable
private fun DateTimePick(initial: Long, zone: java.time.ZoneId, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val local = Instant.ofEpochMilli(initial).atZone(zone)
    var date by remember { mutableStateOf<LocalDate?>(null) }
    if (date == null) {
        val st = rememberDatePickerState(initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { st.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } }) { Text("Next") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { DatePicker(st) }
    } else {
        val tp = rememberTimePickerState(local.hour, local.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(date!!.format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.US))) },
            text = { TimePicker(tp) },
            confirmButton = { TextButton(onClick = { onPick(date!!.atTime(LocalTime.of(tp.hour, tp.minute)).atZone(zone).toInstant().toEpochMilli()) }) { Text("OK") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}
