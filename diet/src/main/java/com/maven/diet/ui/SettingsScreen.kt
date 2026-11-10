package com.maven.diet.ui

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.maven.diet.BuildConfig
import com.maven.diet.Graph
import com.maven.diet.core.Actions
import com.maven.diet.core.DietData
import com.maven.diet.core.Fasting
import com.maven.diet.core.Goal
import com.maven.diet.core.Num
import com.maven.diet.core.Profile
import com.maven.diet.core.Settings
import com.maven.diet.core.Sex
import com.maven.diet.core.Slot
import com.maven.diet.core.Targets
import com.maven.diet.link.Health
import com.maven.diet.link.WorkoutLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(d: DietData, resumes: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = d.settings
    val p = d.profile
    fun set(f: (Settings) -> Settings) = Graph.update { Actions.settings(it, f) }
    fun prof(f: (Profile) -> Profile) = Graph.update { Actions.profile(it, f) }
    var importing by remember { mutableStateOf(false) }
    val granted by produceState(emptySet<String>(), resumes) { value = runCatching { Health.granted(ctx) }.getOrDefault(emptySet()) }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch(Dispatchers.IO) { runCatching { Health.pull(ctx) } }
    }
    val workout = remember(resumes) { WorkoutLink.connected(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel { com.maven.feedback.FeedbackSettings("Not a Twig") }
        Panel {
            SectionLabel("You")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Height (in)", p.heightIn, Modifier.weight(1f)) { v -> prof { it.copy(heightIn = v) } }
                NumField("Age", p.age.toDouble(), Modifier.weight(1f)) { v -> prof { it.copy(age = v.toInt()) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Start weight (lb)", p.weightLb, Modifier.weight(1f)) { v -> prof { it.copy(weightLb = v) } }
                NumField("Body fat % (blank = unknown)", p.bodyFatPct, Modifier.weight(1f), allowBlank = true) { v -> prof { it.copy(bodyFatPct = v.takeIf { x -> x > 0 }) } }
            }
            ChipRow(Sex.entries, { it == p.sex }, { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }) { v -> prof { it.copy(sex = v) } }
            SectionLabel("Goal")
            ChipRow(Goal.entries, { it == p.goal }, { it.label }) { v -> prof { it.copy(goal = v) } }
            Dim("Formula maintenance now: ${Targets.formulaMaintenance(d, Targets.currentWeight(d, Graph.today())).roundToInt()} kcal (BMR ${Targets.bmr(p, Targets.currentWeight(d, Graph.today())).roundToInt()} × ${s.activityFactor}).")
        }
        Panel {
            SectionLabel("Macros")
            Text("Protein per lb")
            ChipRow(listOf(0.8, 0.9, 0.95, 1.0, 1.1), { it == s.proteinPerLb }, { Num.fmt(it) }) { v -> set { it.copy(proteinPerLb = v) } }
            Text("Fat per lb")
            ChipRow(listOf(0.3, 0.35, 0.4, 0.45), { it == s.fatPerLb }, { Num.fmt(it) }) { v -> set { it.copy(fatPerLb = v) } }
            Text("Activity factor (until your own estimate takes over)")
            ChipRow(listOf(1.3, 1.4, 1.45, 1.5, 1.6, 1.7), { it == s.activityFactor }, { Num.fmt(it) }) { v -> set { it.copy(activityFactor = v) } }
            Stepper("Training day +/− kcal", s.cycleKcal, 0..500, step = 25) { v -> set { it.copy(cycleKcal = v) } }
            Text("Eat back extra activity")
            ChipRow(listOf(0.0, 0.5, 0.7, 1.0), { it == s.eatBackShare }, { "${(it * 100).roundToInt()}%" }) { v -> set { it.copy(eatBackShare = v) } }
        }
        Panel {
            SectionLabel("Intermittent fasting")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Eating window", Modifier.weight(1f))
                Switch(s.fasting, { v -> set { it.copy(fasting = v) } })
            }
            if (s.fasting) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeButton(s.windowStart) { v -> set { it.copy(windowStart = v) } }
                Text("to")
                TimeButton(s.windowEnd) { v -> set { it.copy(windowEnd = v) } }
            }
            Dim("Food outside the window is still logged — the comparison needs honest data, not perfect days.")
        }
        Panel {
            SectionLabel("Meals (nag if empty by)")
            for ((i, slot) in s.slots.withIndex()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                var name by remember(i, slot.name) { mutableStateOf(slot.name) }
                OutlinedTextField(name, { name = it; if (it.isNotBlank()) set { st -> st.copy(slots = st.slots.mapIndexed { j, x -> if (j == i) x.copy(name = it.trim()) else x }) } },
                    singleLine = true, modifier = Modifier.weight(1f))
                val by = slot.by
                if (by != null) TimeButton(by) { v -> set { st -> st.copy(slots = st.slots.mapIndexed { j, x -> if (j == i) x.copy(by = v) else x }) } }
                else TextButton(onClick = { set { st -> st.copy(slots = st.slots.mapIndexed { j, x -> if (j == i) x.copy(by = "12:00") else x }) } }) { Text("no nag") }
                IconButton(onClick = { set { st -> st.copy(slots = st.slots.filterIndexed { j, _ -> j != i }) } }, enabled = s.slots.size > 1) { Icon(Icons.Filled.Close, "Remove") }
            }
            OutlinedButton(onClick = { set { it.copy(slots = it.slots + Slot("Meal ${it.slots.size + 1}")) } }) { Text("+ Add meal") }
        }
        Panel {
            SectionLabel("Water")
            Stepper("Daily oz", s.waterOz.roundToInt(), 32..256, step = 4) { v -> set { it.copy(waterOz = v.toDouble()) } }
            Stepper("Extra on training days", s.waterTrainingExtraOz.roundToInt(), 0..64, step = 4) { v -> set { it.copy(waterTrainingExtraOz = v.toDouble()) } }
        }
        Panel {
            SectionLabel("Nagging")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Nag when food / water / weigh-in is late", Modifier.weight(1f))
                Switch(s.nags, { v -> set { it.copy(nags = v) } })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show reminders here (turn off if Not a Fraud shows them)", Modifier.weight(1f))
                Switch(s.notifyHere, { v -> set { it.copy(notifyHere = v) } })
            }
            Stepper("Get louder every (min)", s.nagEveryMin, 15..120, step = 15) { v -> set { it.copy(nagEveryMin = v) } }
            for ((label, value, apply) in listOf<Triple<String, String, (String) -> Unit>>(
                Triple("Awake from", s.wake) { v -> set { it.copy(wake = v) } },
                Triple("Quiet after", s.bed) { v -> set { it.copy(bed = v) } },
                Triple("Weigh in by", s.weighInBy) { v -> set { it.copy(weighInBy = v) } },
                Triple("Check-in after", s.checkInAfter) { v -> set { it.copy(checkInAfter = v) } },
            )) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f))
                TimeButton(value, apply)
            }
        }
        Panel {
            SectionLabel("Connections")
            val hcOk = Health.ACTIVE in granted
            Text(if (hcOk) "Samsung Health (Health Connect): connected" else "Samsung Health (Health Connect): not connected", color = if (hcOk) Palette.green else Palette.amber)
            Dim("Active calories + steps, so a big day earns more food. In Samsung Health → Settings → Health Connect, let it write activity. ${Health.lastResult}")
            if (!hcOk) OutlinedButton(onClick = {
                if (Health.available(ctx)) hcLauncher.launch(Health.PERMISSIONS)
                else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))) }
            }) { Text("Connect") }
            Text(if (workout) "Not a Troglodyte: connected" else "Not a Troglodyte: not found", color = if (workout) Palette.green else Palette.amber)
            Dim("Training vs rest day and lifting times come from the workout app. Not a Fraud reads this app the same way.")
        }
        Panel {
            SectionLabel("Backup")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val text = Graph.store.exportJson()
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("diet data", text))
                    Toast.makeText(ctx, "Copied ${text.length / 1024} KB", Toast.LENGTH_SHORT).show()
                }) { Text("Copy all data") }
                OutlinedButton(onClick = { importing = true }) { Text("Import…") }
            }
            Dim("Not a Twig ${BuildConfig.VERSION_NAME} · ${d.log.size} entries · ${d.foods.size} foods")
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

@Composable
fun TimeButton(value: String, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val t = Fasting.time(value)
    OutlinedButton(onClick = {
        TimePickerDialog(ctx, { _, h, m -> onPick("%02d:%02d".format(h, m)) }, t.hour, t.minute, android.text.format.DateFormat.is24HourFormat(ctx)).show()
    }) { Text(t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))) }
}

@Composable
private fun NumField(label: String, value: Double?, modifier: Modifier, allowBlank: Boolean = false, onValue: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(Num.fmt(value)) }
    OutlinedTextField(
        text, { t ->
            text = t.filter { it.isDigit() || it == '.' }.take(6)
            val v = text.toDoubleOrNull()
            if (v != null) onValue(v) else if (allowBlank && text.isEmpty()) onValue(0.0)
        },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
