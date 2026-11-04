package com.maven.diet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.maven.diet.Graph
import com.maven.diet.core.Actions
import com.maven.diet.core.DietData
import com.maven.diet.core.Experiment
import com.maven.diet.core.Experiments
import com.maven.diet.core.WeeklyReview
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Last week in one card (shown on Progress, and as a Monday notification). */
@Composable
fun WeeklyReviewPanel(d: DietData) {
    val today = Graph.today()
    val r = remember(d) { WeeklyReview.build(d, WeeklyReview.lastWeekStart(today), today) }
    Panel(highlight = true) {
        SectionLabel("Last week (from ${r.weekStart.format(DateTimeFormatter.ofPattern("MMM d"))})")
        Text(r.suggestion, style = MaterialTheme.typography.titleSmall)
        Dim(listOfNotNull(
            "${r.completeDays}/7 complete days, ${r.weighIns} weigh-ins",
            r.avgKcal?.let { k -> "ate ${k.roundToInt()}" + (r.avgTargetKcal?.let { " of ${it.roundToInt()}" } ?: "") + " kcal a day" },
            r.avgProtein?.let { p -> "protein ${p.roundToInt()}" + (r.proteinTarget?.let { " of ${it.roundToInt()}" } ?: "") + " g" },
            if (r.trendStart != null && r.trendEnd != null) "trend ${"%.1f".format(r.trendStart)} → ${"%.1f".format(r.trendEnd)} lb" else null,
            r.maintenance?.let { "your maintenance ≈ ${it.roundToInt()}" + if (r.maintenanceFromYou) " (measured)" else " (still learning)" },
            r.avgSleep?.let { "sleep ${"%.1f".format(it)} h" },
            r.avgEnergy?.let { "energy ${"%.1f".format(it)}/5" },
            r.avgClarity?.let { "clarity ${"%.1f".format(it)}/5" },
        ).joinToString(" · "))
    }
}

/** Today's experiment day: which condition, did you follow it, how did the workout feel. */
@Composable
fun ExperimentToday(d: DietData) {
    val today = Graph.today()
    for (e in Experiments.active(d, today)) {
        val c = Experiments.conditionOn(e, today) ?: continue
        val followed = e.followed[today.toString()]
        Panel {
            SectionLabel("Experiment · ${e.question}")
            Text("Today: ${if (c == 'A') e.conditionA else e.conditionB}", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(followed == true, { Graph.update { Actions.markExperimentDay(it, e.id, today, if (followed == true) null else true) } }, { Text("Did it") })
                FilterChip(followed == false, { Graph.update { Actions.markExperimentDay(it, e.id, today, if (followed == false) null else false) } }, { Text("Didn't") })
            }
            Dim("Workout felt (optional)")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (v in 1..5) FilterChip(e.gym[today.toString()] == v, { Graph.update { Actions.markExperimentDay(it, e.id, today, followed, v) } }, { Text("$v") })
            }
            Dim("The check-in's clarity and energy are what's compared. Days marked \"Didn't\" are left out.")
        }
    }
}

/** Experiments on Progress: results with how sure they are, plus starting a new one. */
@Composable
fun ExperimentsPanel(d: DietData) {
    var adding by remember { mutableStateOf(false) }
    Panel {
        SectionLabel("Experiments")
        if (d.experiments.isEmpty()) Dim("Pick a question (chocolate milk 15 min vs a meal 3 h before the gym; 16:8 vs 12:12 fasting). Days alternate between the two, and the app compares your energy, clarity and workouts.")
        for (e in d.experiments.sortedByDescending { it.start }) {
            Text(e.question + if (e.ended) " (ended)" else "", style = MaterialTheme.typography.titleSmall)
            Dim("A: ${e.conditionA} · B: ${e.conditionB} · ${e.days} days from ${LocalDate.parse(e.start).format(DateTimeFormatter.ofPattern("MMM d"))} · ${e.followed.count { it.value }} days followed")
            for (r in Experiments.results(d, e)) Text(
                "${r.metric}: A ${"%.1f".format(r.a)} vs B ${"%.1f".format(r.b)} (n ${r.nA}/${r.nB}) · ${r.verdict}",
                style = MaterialTheme.typography.bodySmall,
                color = if (r.verdict == "likely real") Palette.green else MaterialTheme.colorScheme.onSurface,
            )
            Row {
                if (!e.ended) TextButton(onClick = { Graph.update { Actions.saveExperiment(it, e.copy(ended = true)) } }) { Text("End it") }
                TextButton(onClick = { Graph.update { Actions.deleteExperiment(it, e.id) } }) { Text("Delete", color = Palette.red) }
            }
        }
        OutlinedButton(onClick = { adding = true }) { Text("New experiment") }
    }
    if (adding) {
        var q by remember { mutableStateOf("") }
        var a by remember { mutableStateOf("") }
        var b by remember { mutableStateOf("") }
        var days by remember { mutableIntStateOf(14) }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New experiment") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(q, { q = it }, label = { Text("Question") }, placeholder = { Text("Pre-workout carbs timing") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(a, { a = it }, label = { Text("Condition A") }, placeholder = { Text("Chocolate milk 15 min before") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(b, { b = it }, label = { Text("Condition B") }, placeholder = { Text("Meal 3 h before") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (n in listOf(10, 14, 20, 28)) FilterChip(days == n, { days = n }, { Text("$n days") })
                    }
                    Dim("Starts today with A, then B, A, B… About 7 followed days of each gives a first answer.")
                }
            },
            confirmButton = {
                TextButton(enabled = q.isNotBlank() && a.isNotBlank() && b.isNotBlank(), onClick = {
                    Graph.update { Actions.saveExperiment(it, Experiment(Actions.newId(), q.trim(), a.trim(), b.trim(), Graph.today().toString(), days)) }
                    adding = false
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}
