@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maven.diet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.maven.diet.core.MealPlanner
import com.maven.diet.core.Num
import kotlin.math.roundToInt

/** Eat This Much, Maven-sized: plan the rest of today from what you eat; groceries for the next few days. */
@Composable
fun PlanScreen(d: DietData) {
    val today = Graph.today()
    var seed by remember { mutableIntStateOf(0) }
    var cheap by remember { mutableStateOf(false) }
    var groceryDays by remember { mutableIntStateOf(0) }
    var logged by remember { mutableStateOf(false) }
    val plan = remember(d, seed, cheap) { MealPlanner.plan(d, today, Graph.targets(today).macros, seed, cheap) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Panel(highlight = true) {
                SectionLabel("Rest of today")
                Dim(plan.note)
                for (s in plan.slots) {
                    Text("${s.slot} · ${s.macros.kcal.roundToInt()} kcal · ${s.macros.protein.roundToInt()} g protein" + (s.cost?.let { " · \$%.2f".format(it) } ?: ""),
                        style = MaterialTheme.typography.titleSmall)
                    for (p in s.items) Dim("  " + when (p) {
                        is MealPlanner.Pick.FoodPick -> "${Num.fmt(p.servings)} × ${p.label}"
                        is MealPlanner.Pick.MealPick -> "${Num.fmt(p.portions)} portion${if (p.portions == 1.0) "" else "s"} ${p.label}"
                    })
                }
                plan.cost?.let { Dim("About \$%.2f".format(it)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { seed++ ; logged = false }) { Text("Shuffle") }
                    FilterChip(cheap, { cheap = !cheap }, { Text("Cheaper") })
                }
                if (plan.slots.isNotEmpty()) Button(onClick = {
                    Graph.update { Actions.logPlan(it, today, plan, Graph.now()) }
                    logged = true
                }, enabled = !logged, modifier = Modifier.fillMaxWidth()) { Text(if (logged) "Logged" else "Log this plan") }
                Dim("Uses your saved meals, recipes and foods you've logged at least twice, at your usual amounts. Prices come from foods you've priced.")
            }
        }
        item {
            Panel {
                SectionLabel("Grocery list")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (n in listOf(3, 5, 7)) FilterChip(groceryDays == n, { groceryDays = if (groceryDays == n) 0 else n }, { Text("$n days") })
                }
                if (groceryDays > 0) {
                    val lines = remember(d, groceryDays, cheap) {
                        val plans = (0 until groceryDays).map { k ->
                            val day = today.plusDays(k.toLong() + 1)
                            MealPlanner.plan(d, day, Graph.targets(day).macros, seed + k, cheap)
                        }
                        MealPlanner.groceries(d, plans)
                    }
                    if (lines.isEmpty()) Dim("Nothing to plan from yet.")
                    for (l in lines) Text("${l.name}: ${l.amount}" + (l.cost?.let { " · \$%.2f".format(it) } ?: ""), style = MaterialTheme.typography.bodyMedium)
                    lines.mapNotNull { it.cost }.takeIf { it.size == lines.size && it.isNotEmpty() }?.sum()?.let { Text("Total about \$%.2f".format(it), style = MaterialTheme.typography.titleSmall) }
                    Dim("Planned days aren't logged; each day gets planned fresh when you get there.")
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
