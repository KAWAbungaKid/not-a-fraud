package com.maven.diet.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.maven.diet.Graph
import com.maven.diet.core.Adaptive
import com.maven.diet.core.Diary
import com.maven.diet.core.DietData
import com.maven.diet.core.Fasting
import com.maven.diet.core.Insights
import com.maven.diet.core.Num
import com.maven.diet.core.Targets
import com.maven.diet.core.Trend
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
fun ProgressScreen(d: DietData) {
    val today = Graph.today()
    val series = remember(d.weights) { Trend.series(d, today).takeLast(60) }
    val est = remember(d) { Adaptive.estimate(d, today) }
    val rate = Trend.ratePerWeek(d, today)
    val comparisons = remember(d) { Insights.comparisons(d, Graph.zone, today) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item { WeeklyReviewPanel(d) }
        item { ExperimentsPanel(d) }
        item {
            Panel {
                SectionLabel("Weight")
                if (series.size >= 2) WeightChart(series) else Dim("The chart starts after two weigh-ins.")
                series.lastOrNull()?.let { Text("Trend ${"%.1f".format(it.third)} lb", style = MaterialTheme.typography.titleMedium) }
                val goal = d.profile.goal.lbPerWeek
                Dim(
                    (rate?.let { "${if (it >= 0) "+" else ""}${"%.2f".format(it)} lb/week over 2 weeks · " } ?: "Rate after ~2 weeks of weigh-ins · ") +
                        "goal ${if (goal >= 0) "+" else ""}$goal (${d.profile.goal.label})",
                )
                if (d.profile.goal.lbPerWeek == 0.0 && rate != null && kotlin.math.abs(rate) > 0.4)
                    Text(if (rate > 0) "Gaining faster than a recomp should — the app will trim calories as your maintenance estimate firms up." else "Losing weight on a recomp — eat closer to target, especially on training days.", color = Palette.amber)
            }
        }
        item {
            Panel {
                SectionLabel("Your maintenance (the metabolism question)")
                if (est == null) {
                    val from = today.minusDays(28)
                    val weighIns = d.weights.count { !LocalDate.parse(it.date).isBefore(from) }
                    val complete = Diary.completeDays(d).count { !it.isBefore(from) && it.isBefore(today) }
                    Text("Learning… needs 2 weeks of data", style = MaterialTheme.typography.titleMedium)
                    Dim("Weigh-ins (last 28 days): $weighIns / 10 · complete food days: $complete / 10. Until then targets use the formula (${Targets.formulaMaintenance(d, Targets.currentWeight(d, today)).roundToInt()} kcal).")
                } else {
                    Text("${est.tdee.roundToInt()} kcal/day", style = MaterialTheme.typography.headlineSmall, color = Palette.accent)
                    Dim("You ate ${est.intake.roundToInt()} on average over ${est.days} days while your trend moved ${"%+.1f".format(est.trendChange)} lb. Formula says ${Targets.formulaMaintenance(d, Targets.currentWeight(d, today)).roundToInt()}. Confidence ${(est.confidence * 100).roundToInt()}%.")
                }
            }
        }
        item {
            val days = (1..7).map { today.minusDays(it.toLong()) }
            val logged = days.filter { Diary.entries(d, it).isNotEmpty() }
            Panel {
                SectionLabel("Last 7 days")
                if (logged.isEmpty()) Dim("Nothing logged yet.")
                else {
                    val avg = logged.map { Diary.totals(d, it) }
                    Row { Text("Calories", Modifier.weight(1f)); Text("${avg.map { it.kcal }.average().roundToInt()} avg", style = Mono) }
                    Row { Text("Protein", Modifier.weight(1f)); Text("${avg.map { it.protein }.average().roundToInt()} g avg", style = Mono) }
                    Row { Text("Water", Modifier.weight(1f)); Text("${days.map { Diary.waterOz(d, it) }.average().roundToInt()} oz avg", style = Mono) }
                    val fasts = days.mapNotNull { Fasting.overnightHours(d, it) }
                    if (fasts.isNotEmpty()) Row { Text("Overnight fast", Modifier.weight(1f)); Text("${"%.1f".format(fasts.average())} h avg", style = Mono) }
                    Dim("${logged.size}/7 days logged · ${days.count { Diary.isComplete(d, it) }} complete")
                }
            }
        }
        item {
            Panel {
                SectionLabel("Brain fog & energy — what's making the difference")
                val n = d.checkins.size
                if (comparisons.isEmpty()) {
                    Dim("Collecting: $n check-in day(s). Comparisons show once there are 5+ days on each side (e.g. 16 h+ fasts vs shorter). Keep fasting some days and not others and it'll have something to compare.")
                } else for (c in comparisons) Column(Modifier.padding(vertical = 4.dp)) {
                    Text("${c.question} → ${c.metric}", style = MaterialTheme.typography.titleSmall)
                    Text("${c.groupA}: ${"%.1f".format(c.meanA)} (n ${c.nA})  vs  ${c.groupB}: ${"%.1f".format(c.meanB)} (n ${c.nB})", style = Mono)
                    Dim(c.verdict)
                }
                Dim("These are patterns, not proof. Clear answers come from deliberate experiments (next version).")
            }
        }
        item {
            val recent = d.checkins.sortedByDescending { it.date }.take(14)
            if (recent.isNotEmpty()) Panel {
                SectionLabel("Check-ins")
                Row { Text("Day", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall); Text("CLEAR", Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall); Text("ENERGY", Modifier.width(56.dp), style = MaterialTheme.typography.labelSmall) }
                for (c in recent) Row {
                    Text(LocalDate.parse(c.date).format(DateTimeFormatter.ofPattern("EEE MMM d")), Modifier.weight(1f))
                    Text("${c.clarity}", Modifier.width(56.dp), style = Mono)
                    Text("${c.energy}", Modifier.width(56.dp), style = Mono)
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun WeightChart(series: List<Triple<LocalDate, Double?, Double>>) {
    val accent = Palette.accent
    val dot = MaterialTheme.colorScheme.onSurfaceVariant
    val values = series.flatMap { listOfNotNull(it.second, it.third) }
    val lo = values.min() - 0.5
    val hi = values.max() + 0.5
    Row {
        Column(Modifier.height(140.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(Num.fmt(Math.round(hi * 10) / 10.0), style = MaterialTheme.typography.labelSmall)
            Text(Num.fmt(Math.round(lo * 10) / 10.0), style = MaterialTheme.typography.labelSmall)
        }
        Canvas(Modifier.fillMaxWidth().height(140.dp).padding(start = 8.dp)) {
            val n = (series.size - 1).coerceAtLeast(1)
            fun x(i: Int) = i.toFloat() / n * size.width
            fun y(v: Double) = (size.height - (v - lo) / (hi - lo) * size.height).toFloat()
            series.forEachIndexed { i, p -> p.second?.let { drawCircle(dot, 4f, Offset(x(i), y(it))) } }
            val path = Path()
            series.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(i), y(p.third)) else path.lineTo(x(i), y(p.third)) }
            drawPath(path, accent, style = Stroke(width = 5f))
        }
    }
}
