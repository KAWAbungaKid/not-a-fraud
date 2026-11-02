package com.maven.workout.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Fix when a workout happened: date, start time and (for finished workouts) end time.
 * An end time earlier than the start time means it ran past midnight.
 */
@Composable
fun TimesDialog(
    title: String, start: Long, end: Long?, confirm: String = "Save",
    onDismiss: () -> Unit, onSave: (start: Long, end: Long?) -> Unit,
) {
    val ctx = LocalContext.current
    val zone = ZoneId.systemDefault()
    val s0 = Instant.ofEpochMilli(start).atZone(zone).toLocalDateTime()
    var date by remember { mutableStateOf(s0.toLocalDate()) }
    var from by remember { mutableStateOf(s0.toLocalTime().withSecond(0).withNano(0)) }
    var to by remember { mutableStateOf(end?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime().withSecond(0).withNano(0) }) }
    val fmt = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a")

    fun startMs(): Long = LocalDateTime.of(date, from).atZone(zone).toInstant().toEpochMilli()
    fun endMs(): Long? = to?.let { t ->
        val day = if (t.isBefore(from)) date.plusDays(1) else date
        LocalDateTime.of(day, t).atZone(zone).toInstant().toEpochMilli()
    }
    fun pickTime(initial: LocalTime, set: (LocalTime) -> Unit) =
        TimePickerDialog(ctx, { _, h, m -> set(LocalTime.of(h, m)) }, initial.hour, initial.minute, DateFormat.is24HourFormat(ctx)).show()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Date", Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        DatePickerDialog(ctx, { _, y, m, dd -> date = LocalDate.of(y, m + 1, dd) }, date.year, date.monthValue - 1, date.dayOfMonth)
                            .apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                    }) { Text(date.format(DateTimeFormatter.ofPattern("EEE MMM d"))) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Started", Modifier.weight(1f))
                    OutlinedButton(onClick = { pickTime(from) { from = it } }) { Text(from.format(fmt)) }
                }
                to?.let { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Finished", Modifier.weight(1f))
                        OutlinedButton(onClick = { pickTime(t) { to = it } }) { Text(t.format(fmt)) }
                    }
                    val mins = ((endMs() ?: 0L) - startMs()) / 60_000
                    Dim("$mins min" + if (t.isBefore(from)) " (past midnight)" else "")
                }
                if (startMs() > System.currentTimeMillis()) Text("That start is in the future.", color = Palette.red)
            }
        },
        confirmButton = {
            TextButton(enabled = startMs() <= System.currentTimeMillis(), onClick = { onSave(startMs(), endMs()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
