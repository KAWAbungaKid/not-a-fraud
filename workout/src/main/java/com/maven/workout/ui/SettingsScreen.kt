package com.maven.workout.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.maven.workout.BuildConfig
import com.maven.workout.Graph
import com.maven.workout.core.Actions
import com.maven.workout.core.Advance
import com.maven.workout.core.Settings
import com.maven.workout.core.Sync
import com.maven.workout.core.WorkoutData
import com.maven.workout.notify.Reminder

@Composable
fun SettingsScreen(d: WorkoutData) {
    val ctx = LocalContext.current
    val s = d.settings
    fun set(f: (Settings) -> Settings) { Graph.update { Actions.settings(it, f) }; Reminder.schedule(ctx) }
    var importing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            SectionLabel("Your week")
            Stepper("Minimum workouts / week", s.minPerWeek, 1..7) { v -> set { it.copy(minPerWeek = v) } }
            val programDays = d.program()?.days?.size ?: s.minPerWeek
            Stepper("Full week (what to plan for)", s.targetPerWeek ?: programDays, 1..7) { v -> set { it.copy(targetPerWeek = if (v == programDays) null else v) } }
            Dim(if (s.targetPerWeek == null) "Following the program ($programDays days)." else "Overridden — program has $programDays days.")
            SectionLabel("Week starts")
            ChipRow(listOf(true, false), { it == s.weekStartsMonday }, { if (it) "Monday" else "Sunday" }) { v -> set { it.copy(weekStartsMonday = v) } }
        }
        Panel {
            SectionLabel("Program week moves on")
            ChipRow(Advance.entries, { it == s.advance }, { it.label }) { v -> set { it.copy(advance = v) } }
            Stepper("Ease back in after (days off, 0 = never)", s.reentryAfterDays, 0..60) { v -> set { it.copy(reentryAfterDays = v) } }
            Dim("After that many days without a workout, the current block restarts at week 1.")
        }
        Panel {
            SectionLabel("Workouts")
            Stepper("Short mode minutes", s.shortMinutes, 10..90, step = 5) { v -> set { it.copy(shortMinutes = v) } }
            Stepper("Rest (sec)", s.restSec, 30..600, step = 15) { v -> set { it.copy(restSec = v) } }
            Stepper("Rest after heavy sets (sec)", s.restHeavySec, 30..600, step = 15) { v -> set { it.copy(restHeavySec = v) } }
            Stepper("Training max window (days)", s.maxWindowDays, 7..182, step = 7) { v -> set { it.copy(maxWindowDays = v) } }
            SectionLabel("Units")
            ChipRow(listOf("lb", "kg"), { it == s.unit }, { it }) { v -> set { it.copy(unit = v) } }
        }
        Panel {
            SectionLabel("Reminder")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Daily check when the minimum is at risk", Modifier.weight(1f))
                Switch(s.reminder, { v -> set { it.copy(reminder = v) } })
            }
            Stepper("At hour (24h)", s.reminderHour, 0..23) { v -> set { it.copy(reminderHour = v) } }
        }
        Panel {
            SectionLabel("Bar and plates")
            Stepper("Bar (${s.unit})", s.barWeight.toInt(), 10..60, step = 5) { v -> set { it.copy(barWeight = v.toDouble()) } }
            var plates by remember { mutableStateOf(s.plates.joinToString(", ") { com.maven.workout.core.Rpe.fmt(it) }) }
            OutlinedTextField(plates, { t ->
                plates = t
                val list = t.split(',', ' ').mapNotNull { it.trim().toDoubleOrNull() }.filter { it > 0 }
                if (list.isNotEmpty()) set { it.copy(plates = list.distinct().sortedDescending()) }
            }, label = { Text("Plates you have (per side)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Dim("Used for \"Plates for 225: 2×45 per side\" and for warm-up sets.")
        }
        Panel {
            SectionLabel("Health Connect")
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            var granted by remember { mutableStateOf<Boolean?>(null) }
            androidx.compose.runtime.LaunchedEffect(Unit) { granted = com.maven.workout.link.HealthWrite.granted(ctx) }
            val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()) { g ->
                granted = com.maven.workout.link.HealthWrite.WRITE in g
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Write finished workouts to Health Connect", Modifier.weight(1f))
                Switch(s.writeHealth, { v -> set { it.copy(writeHealth = v) } })
            }
            Dim(when (granted) { true -> "Allowed. Samsung Health sees your lifts; Not a Fraud doesn't count them as cardio."; false -> "Not allowed yet."; null -> "Checking…" })
            if (granted == false && com.maven.workout.link.HealthWrite.available(ctx)) OutlinedButton(onClick = { launcher.launch(setOf(com.maven.workout.link.HealthWrite.WRITE)) }) { Text("Allow") }
        }
        Panel {
            SectionLabel("Import from Hevy")
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            var result by remember { mutableStateOf<String?>(null) }
            val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                scope.launch {
                    val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.getOrNull() }
                    if (text == null) { result = "Couldn't read that file."; return@launch }
                    var r: com.maven.workout.core.HevyImport.Result? = null
                    Graph.update { val x = com.maven.workout.core.HevyImport.import(it, text, java.time.ZoneId.systemDefault()); r = x; x.data }
                    result = r?.let { x ->
                        "Imported ${x.sessions} workouts (${x.sets} sets)." + (if (x.newExercises.isNotEmpty()) " New exercises: ${x.newExercises.joinToString()}. Give them a type in Exercises." else "") +
                            (if (x.skipped > 0) " Skipped ${x.skipped} rows (already here or unreadable)." else "")
                    }
                }
            }
            Dim("In Hevy: Profile → ⚙ → Export & import data → Export workouts (CSV). Then pick the file here. e1RMs and history start full; importing the same file twice adds nothing.")
            OutlinedButton(onClick = { pick.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/octet-stream")) }) { Text("Pick Hevy CSV…") }
            result?.let { Text(it, color = Palette.green) }
        }
        Panel { com.maven.feedback.FeedbackSettings("Not a Troglodyte") }
        Panel {
            SectionLabel("Not a Fraud link")
            val st = Sync.status(d, Graph.today(), Graph.now())
            Dim("Not a Fraud reads this (same signing key): ${st.doneThisWeek}/${st.minPerWeek} min, ${st.targetPerWeek} full, risk ${st.risk}, today: ${st.suggestedToday ?: "—"}.")
            Dim("content://${BuildConfig.APPLICATION_ID}.sync/status")
        }
        Panel {
            SectionLabel("Backup")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(ctx, Graph.store.exportJson()) }) { Text("Copy all data") }
                OutlinedButton(onClick = { importing = true }) { Text("Import…") }
            }
            Dim("Not a Troglodyte ${BuildConfig.VERSION_NAME} · ${d.sessions.size} workouts · ${d.exercises.size} exercises")
        }
        Spacer(Modifier.height(16.dp))
    }
    if (importing) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importing = false },
            title = { Text("Import data") },
            text = { OutlinedTextField(text, { text = it }, label = { Text("Paste exported JSON — replaces everything") }, modifier = Modifier.fillMaxWidth().height(200.dp)) },
            confirmButton = {
                TextButton(onClick = {
                    val r = Graph.store.importJson(text)
                    Toast.makeText(ctx, if (r.isSuccess) "Imported" else "Couldn't read that", Toast.LENGTH_SHORT).show()
                    if (r.isSuccess) importing = false
                }) { Text("Replace my data") }
            },
            dismissButton = { TextButton(onClick = { importing = false }) { Text("Cancel") } },
        )
    }
}

private fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("workout data", text))
    Toast.makeText(ctx, "Copied ${text.length / 1024} KB", Toast.LENGTH_SHORT).show()
}
