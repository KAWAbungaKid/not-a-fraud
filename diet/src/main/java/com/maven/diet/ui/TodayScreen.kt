package com.maven.diet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maven.diet.Graph
import com.maven.diet.core.Actions
import com.maven.diet.core.Diary
import com.maven.diet.core.DietData
import com.maven.diet.core.Fasting
import com.maven.diet.core.LogEntry
import com.maven.diet.core.Nags
import com.maven.diet.core.Num
import com.maven.diet.core.Nutrient
import com.maven.diet.core.Remedies
import com.maven.diet.core.Trend
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun TodayScreen(d: DietData, onOpen: (Route) -> Unit) {
    var date by remember { mutableStateOf(Graph.today()) }
    val now by produceState(Graph.now()) { while (true) { value = Graph.now(); delay(30_000) } }
    val isToday = date == Graph.today()
    val t = remember(d, date, now / 60_000) { Graph.targets(date) }
    val eaten = Diary.totals(d, date)
    var why by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<LogEntry?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                IconButton(onClick = { date = date.minusDays(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (isToday) "Today" else date.format(DateTimeFormatter.ofPattern("EEE, MMM d")), style = MaterialTheme.typography.titleLarge)
                    if (isToday) Dim(date.format(DateTimeFormatter.ofPattern("EEEE, MMM d")))
                }
                IconButton(onClick = { date = date.plusDays(1) }, enabled = !isToday) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day") }
            }
        }

        if (isToday) {
            val nags = Nags.due(d, now, Graph.zone, t.training)
            if (nags.isNotEmpty()) item {
                Panel {
                    SectionLabel("Overdue")
                    for (n in nags.sortedByDescending { it.level }) Text(
                        (if (n.level >= 3) "‼ " else if (n.level == 2) "! " else "• ") + n.title,
                        color = when (n.level) { 3 -> Palette.red; 2 -> Palette.amber; else -> MaterialTheme.colorScheme.onSurface },
                    )
                }
            }
        }

        // Totals
        item {
            Panel(highlight = true) {
                val left = t.macros.kcal - eaten.kcal
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${eaten.kcal.roundToInt()}", style = MaterialTheme.typography.displaySmall, color = Palette.accent)
                    Text(" / ${t.macros.kcal.roundToInt()} kcal", style = Mono.copy(fontSize = 16.sp), modifier = Modifier.padding(bottom = 6.dp))
                    Spacer(Modifier.weight(1f))
                    Text(if (left >= 0) "${left.roundToInt()} left" else "${(-left).roundToInt()} over", style = Mono,
                        color = if (left >= 0) MaterialTheme.colorScheme.onSurface else Palette.amber, modifier = Modifier.padding(bottom = 6.dp))
                }
                MacroBar("Protein", eaten.protein, t.macros.protein, "g", Palette.accent)
                MacroBar("Carbs", eaten.carbs, t.macros.carbs, "g", Palette.green)
                MacroBar("Fat", eaten.fat, t.macros.fat, "g", Palette.amber)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(t.training, { Graph.update { Actions.setTraining(it, date, true) } }, { Text("Training day") })
                    FilterChip(!t.training, { Graph.update { Actions.setTraining(it, date, false) } }, { Text("Rest day") })
                    if (d.trainingOverride.containsKey(date.toString())) TextButton(onClick = { Graph.update { Actions.setTraining(it, date, null) } }) { Text("Auto") }
                }
                TextButton(onClick = { why = !why }) { Text(if (why) "Hide the math" else "Where these numbers come from") }
                if (why) for (l in t.lines) Dim("• $l")
            }
        }

        if (isToday && d.settings.fasting) item {
            val f = Fasting.status(d, now, Graph.zone)
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (f.inWindow) "Eating window" else "Fasting", style = MaterialTheme.typography.titleMedium,
                            color = if (f.inWindow) Palette.green else Palette.accent)
                        Dim("${d.settings.windowStart}–${d.settings.windowEnd} · " +
                            (if (f.inWindow) "closes in " else "opens in ") + hm(f.minutesToChange))
                    }
                    Text(f.hoursSinceFood?.let { "${hm((it * 60).toLong())} since food" } ?: "—", style = Mono)
                }
                if (f.outsideWindow > 0) Dim("${f.outsideWindow} item(s) logged outside the window today — recorded honestly, it feeds the fasting comparison.")
            }
        }

        if (isToday) item { CheckInPanel(d, now) }

        item { WaterPanel(d, date, t.waterOz) }
        item { WeightPanel(d, date) }

        // Meals
        for (slot in d.settings.slots) item {
            val es = Diary.entries(d, date).filter { it.slot == slot.name }.sortedBy { it.at }
            val st = Diary.slotTotals(d, date, slot.name)
            val yesterday = Diary.entries(d, date.minusDays(1)).any { it.slot == slot.name }
            var saving by remember { mutableStateOf(false) }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(slot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (slot.by != null) Dim("by ${slot.by}")
                    }
                    Text("${st.kcal.roundToInt()} kcal · ${st.protein.roundToInt()} P", style = Mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (e in es) {
                    val f = d.food(e.foodId)
                    val m = Diary.macrosOf(d, e)
                    Row(Modifier.fillMaxWidth().clickable { editing = e }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f?.name ?: "(deleted food)")
                            Dim("${Num.fmt(e.servings)} × ${f?.serving ?: ""}")
                        }
                        Text("${m.kcal.roundToInt()}", style = Mono)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpen(Route.AddFood(date.toString(), slot.name)) }) { Text("+ Add") }
                    if (es.isEmpty() && yesterday) OutlinedButton(onClick = {
                        Graph.update { Actions.copySlot(it, date.minusDays(1), date, slot.name, logTime(date)) }
                    }) { Text("Same as yesterday") }
                    if (es.isNotEmpty()) TextButton(onClick = { saving = true }) { Text("Save as meal") }
                }
            }
            if (saving) NameDialog("Save ${slot.name} as a meal", "My usual ${slot.name.lowercase()}", onDismiss = { saving = false }) { name ->
                Graph.update { Actions.saveSlotAsMeal(it, date, slot.name, name) }
                saving = false
            }
        }

        item {
            var open by remember { mutableStateOf(false) }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("That's everything I ate", Modifier.weight(1f))
                    Switch(Diary.isComplete(d, date), { v -> Graph.update { Actions.setComplete(it, date, v) } })
                }
                Dim("Complete days teach the app your real maintenance. Every slot with a time filled counts automatically.")
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide nutrients" else "Fiber, sodium & micronutrients") }
                if (open) {
                    val n = Diary.nutrients(d, date)
                    for (k in Nutrient.entries) {
                        val (v, cov) = n.getValue(k)
                        val tgt = k.dailyTarget
                        Row {
                            Text(k.label, Modifier.weight(1f))
                            Text("${Num.fmt((v * 10).roundToInt() / 10.0)} ${k.unit}" + (tgt?.let { " / ${Num.fmt(it)}" } ?: ""), style = Mono,
                                color = when {
                                    tgt == null -> MaterialTheme.colorScheme.onSurface
                                    k.isLimit && v > tgt -> Palette.amber
                                    !k.isLimit && v >= tgt -> Palette.green
                                    else -> MaterialTheme.colorScheme.onSurface
                                })
                        }
                        if (cov < 0.8 && Diary.entries(d, date).isNotEmpty()) Dim("  only ${(cov * 100).roundToInt()}% of today's food lists this")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    editing?.let { e ->
        EntryDialog(d, e, onDismiss = { editing = null })
    }
}

fun hm(min: Long): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

/** Logging for a past day lands at noon of that day; for today, now. */
fun logTime(date: LocalDate): Long =
    if (date == Graph.today()) Graph.now() else date.atTime(12, 0).atZone(Graph.zone).toInstant().toEpochMilli()

@Composable
fun MacroBar(label: String, value: Double, target: Double, unit: String, color: Color) {
    val frac = if (target <= 0) 0f else (value / target).toFloat().coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${value.roundToInt()} / ${target.roundToInt()} $unit", style = Mono.copy(fontSize = 13.sp),
                color = if (value > target * 1.1) Palette.amber else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(color, RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
private fun CheckInPanel(d: DietData, now: Long) {
    val today = Graph.today()
    val c = d.checkins.firstOrNull { it.date == today.toString() }
    var clarity by remember(c) { mutableStateOf(c?.clarity) }
    var energy by remember(c) { mutableStateOf(c?.energy) }
    fun save() { val a = clarity; val b = energy; if (a != null && b != null) Graph.update { Actions.checkIn(it, today, a, b, Graph.now()) } }
    Panel {
        SectionLabel("Check-in")
        Text("Brain fog", style = MaterialTheme.typography.bodyMedium)
        ScaleRow(clarity, "foggy", "clear") { clarity = it; save() }
        Text("Energy (gym / cardio)", style = MaterialTheme.typography.bodyMedium)
        ScaleRow(energy, "drained", "lots") { energy = it; save() }
        if (c == null && (clarity == null || energy == null)) Dim("Two taps a day. This is what the fasting / food comparisons run on.")
        if (c != null) {
            val r = Remedies.forToday(d, now, Graph.zone, Graph.targets(today))
            if (r.isNotEmpty()) {
                SectionLabel("Rough day — try the basics first")
                for (s in r) Column {
                    Text(s.title, color = Palette.accent)
                    Dim(s.why)
                }
            }
        }
    }
}

@Composable
private fun ScaleRow(value: Int?, low: String, high: String, onPick: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Dim(low, Modifier.width(48.dp))
        for (i in 1..5) FilterChip(value == i, { onPick(i) }, { Text("$i") })
        Dim(high)
    }
}

@Composable
private fun WaterPanel(d: DietData, date: LocalDate, target: Double) {
    val have = Diary.waterOz(d, date)
    Panel {
        MacroBar("Water", have, target, "oz", Palette.accent)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (oz in listOf(8.0, 16.0, 24.0)) OutlinedButton(onClick = { Graph.update { Actions.addWater(it, date, oz, logTime(date)) } }) { Text("+${oz.toInt()}") }
            TextButton(onClick = { Graph.update { Actions.addWater(it, date, -8.0, logTime(date)) } }, enabled = have >= 8) { Text("−8") }
        }
    }
}

@Composable
private fun WeightPanel(d: DietData, date: LocalDate) {
    val todays = d.weights.firstOrNull { it.date == date.toString() }
    var text by remember(todays) { mutableStateOf(todays?.lb?.let { Num.fmt(it) } ?: "") }
    val trend = Trend.latest(d, date)
    val rate = Trend.ratePerWeek(d, date)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it.filter { c -> c.isDigit() || c == '.' }.take(6) }, label = { Text("Weight (lb)") },
                singleLine = true, modifier = Modifier.weight(1f),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
            Button(enabled = text.toDoubleOrNull() != null && text.toDoubleOrNull() != todays?.lb, onClick = {
                text.toDoubleOrNull()?.let { lb -> Graph.update { Actions.setWeight(it, date, lb, logTime(date)) } }
            }) { Text(if (todays == null) "Save" else "Update") }
        }
        Dim(buildString {
            append(trend?.let { "Trend ${"%.1f".format(it)} lb" } ?: "Weigh in every morning, same conditions (after the bathroom, before food).")
            rate?.let { append(" · ${if (it >= 0) "+" else ""}${"%.2f".format(it)} lb/week") }
        })
    }
}

@Composable
private fun EntryDialog(d: DietData, e: LogEntry, onDismiss: () -> Unit) {
    val f = d.food(e.foodId)
    var servings by remember { mutableStateOf(Num.fmt(e.servings)) }
    var slot by remember { mutableStateOf(e.slot) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f?.name ?: "Entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(servings, { servings = it }, label = { Text("Servings of ${f?.serving ?: ""}") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                ChipRow(d.settings.slots.map { it.name }, { it == slot }, { it }) { slot = it }
            }
        },
        confirmButton = {
            TextButton(enabled = (servings.toDoubleOrNull() ?: 0.0) > 0, onClick = {
                Graph.update { Actions.editEntry(it, e.id, servings.toDoubleOrNull(), slot) }; onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { Graph.update { Actions.removeEntry(it, e.id) }; onDismiss() }) { Text("Delete", color = Palette.red) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
