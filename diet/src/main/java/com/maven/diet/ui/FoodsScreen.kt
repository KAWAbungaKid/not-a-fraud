package com.maven.diet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maven.diet.Graph
import com.maven.diet.core.Actions
import com.maven.diet.core.Diary
import com.maven.diet.core.DietData
import com.maven.diet.core.Food
import com.maven.diet.core.FoodUnit
import com.maven.diet.core.Units
import com.maven.diet.core.Macros
import com.maven.diet.core.MealItem
import com.maven.diet.core.Num
import com.maven.diet.core.Nutrient
import com.maven.diet.core.SavedMeal
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

fun macroLine(m: Macros) = "${m.kcal.roundToInt()} kcal · ${m.protein.roundToInt()}P ${m.carbs.roundToInt()}C ${m.fat.roundToInt()}F"
fun foodMacros(f: Food) = Macros(f.kcal, f.protein, f.carbs, f.fat)
fun mealMacros(d: DietData, m: SavedMeal): Macros =
    m.items.fold(Macros.ZERO) { acc, it -> acc + (d.food(it.foodId)?.let { f -> foodMacros(f) * it.servings } ?: Macros.ZERO) } * (1.0 / m.servings.coerceAtLeast(0.01))

private fun matches(q: String, vararg s: String) = q.isBlank() || s.any { it.contains(q.trim(), ignoreCase = true) }

@Composable
fun FoodsScreen(d: DietData, onOpen: (Route) -> Unit) {
    var q by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpen(Route.FoodEdit(null)) }) { Text("+ Food from label") }
                    OutlinedButton(onClick = {
                        var id = ""
                        Graph.update { id = Actions.slug("meal", it.meals.map { m -> m.id }.toSet()); Actions.saveMeal(it, SavedMeal(id, "New meal", emptyList())) }
                        onOpen(Route.MealEdit(id))
                    }) { Text("+ Meal / recipe") }
                }
            }
        }
        val meals = d.meals.filter { matches(q, it.name) }
        if (meals.isNotEmpty()) item { SectionLabel("Meals & recipes") }
        items(meals, key = { "m-" + it.id }) { m ->
            Panel(Modifier.clickable { onOpen(Route.MealEdit(m.id)) }) {
                Text(m.name, style = MaterialTheme.typography.titleMedium)
                Dim("per portion: " + macroLine(mealMacros(d, m)) + if (m.servings != 1.0) " · makes ${Num.fmt(m.servings)}" else "")
            }
        }
        val mine = d.foods.filter { it.source != "builtin" && matches(q, it.name, it.brand) }.sortedBy { it.name.lowercase() }
        item { SectionLabel("Your foods (from labels)") }
        if (mine.isEmpty()) item { Dim("None yet. Next time you eat something new, copy its label in once — after that it's a tap.") }
        items(mine, key = { "f-" + it.id }) { f -> FoodRow(f) { onOpen(Route.FoodEdit(f.id)) } }
        val builtin = d.foods.filter { it.source == "builtin" && matches(q, it.name) }.sortedBy { it.name.lowercase() }
        item { SectionLabel("Built-in staples (approximate)") }
        items(builtin, key = { "b-" + it.id }) { f -> FoodRow(f) { onOpen(Route.FoodEdit(f.id)) } }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun FoodRow(f: Food, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(f.name + if (f.archived) " (archived)" else "", color = if (f.archived) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Dim(listOf(f.brand, f.serving).filter { it.isNotBlank() }.joinToString(" · "))
        }
        Text(macroLine(foodMacros(f)).substringBefore(" ·"), style = Mono)
    }
}

@Composable
fun AddFoodScreen(d: DietData, date: String, slot: String, onOpen: (Route) -> Unit, onDone: () -> Unit) {
    val day = LocalDate.parse(date)
    var q by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf<Food?>(null) }
    var pickedMeal by remember { mutableStateOf<SavedMeal?>(null) }
    val recent = Diary.recentFoods(d)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, label = { Text("Search foods and meals") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { onOpen(Route.FoodEdit(null)) }) { Text("+ New food from a label") }
            }
        }
        val meals = d.meals.filter { it.items.isNotEmpty() && matches(q, it.name) }
        if (meals.isNotEmpty()) item { SectionLabel("Meals & recipes") }
        items(meals, key = { "m-" + it.id }) { m ->
            Row(Modifier.fillMaxWidth().clickable { pickedMeal = m }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(m.name, Modifier.weight(1f))
                Text(macroLine(mealMacros(d, m)).substringBefore(" ·"), style = Mono)
            }
        }
        val rec = recent.filter { matches(q, it.name, it.brand) }
        if (rec.isNotEmpty() && q.isBlank()) {
            item { SectionLabel("Recent") }
            items(rec, key = { "r-" + it.id }) { f -> FoodRow(f) { picked = f } }
        }
        val all = d.foods.filter { !it.archived && matches(q, it.name, it.brand) && (q.isNotBlank() || it !in rec) }
            .sortedWith(compareBy({ it.source == "builtin" }, { it.name.lowercase() }))
        item { SectionLabel(if (q.isBlank()) "All foods" else "Matches") }
        items(all, key = { "a-" + it.id }) { f -> FoodRow(f) { picked = f } }
        item { Spacer(Modifier.height(16.dp)) }
    }
    picked?.let { f ->
        AmountDialog(d, f, onDismiss = { picked = null }) { servings, amount, unit ->
            Graph.update { Actions.logFood(it, day, slot, f.id, servings, logTime(day), amount, unit) }
            picked = null; onDone()
        }
    }
    pickedMeal?.let { m ->
        var portions by remember(m.id) { mutableStateOf("1") }
        AlertDialog(
            onDismissRequest = { pickedMeal = null },
            title = { Text(m.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(portions, { portions = it }, label = { Text("Portions") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Dim(macroLine(mealMacros(d, m) * (portions.toDoubleOrNull() ?: 0.0)))
                }
            },
            confirmButton = {
                TextButton(enabled = (portions.toDoubleOrNull() ?: 0.0) > 0, onClick = {
                    Graph.update { Actions.logMeal(it, day, slot, m.id, portions.toDouble(), logTime(day)) }
                    pickedMeal = null; onDone()
                }) { Text("Log") }
            },
            dismissButton = { TextButton(onClick = { pickedMeal = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LabelField(label: String, v: String, numeric: Boolean, mod: Modifier = Modifier.fillMaxWidth(), set: (String) -> Unit) =
    OutlinedTextField(v, set, label = { Text(label) }, singleLine = true, modifier = mod,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default)

/** Copy a nutrition label. Big four required; everything else optional. */
@Composable
fun FoodEditScreen(d: DietData, id: String?, onDone: () -> Unit) {
    val f = id?.let { d.food(it) }
    var name by remember { mutableStateOf(f?.name ?: "") }
    var brand by remember { mutableStateOf(f?.brand ?: "") }
    var serving by remember { mutableStateOf(f?.serving ?: "") }
    var grams by remember { mutableStateOf(Num.fmt(f?.servingGrams)) }
    var ml by remember { mutableStateOf(Num.fmt(f?.servingMl)) }
    val units = remember { (f?.units ?: emptyList()).map { mutableStateOf(it.name) to mutableStateOf(Num.fmt(it.servings)) }.toMutableStateList() }
    var scanNote by remember { mutableStateOf<String?>(null) }
    var kcal by remember { mutableStateOf(Num.fmt(f?.kcal)) }
    var p by remember { mutableStateOf(Num.fmt(f?.protein)) }
    var c by remember { mutableStateOf(Num.fmt(f?.carbs)) }
    var fat by remember { mutableStateOf(Num.fmt(f?.fat)) }
    val nut = remember { Nutrient.entries.associateWith { mutableStateOf(Num.fmt(f?.nutrients?.get(it))) } }
    var archived by remember { mutableStateOf(f?.archived ?: false) }
    val ok = name.isNotBlank() && kcal.toDoubleOrNull() != null && p.toDoubleOrNull() != null && c.toDoubleOrNull() != null && fat.toDoubleOrNull() != null
    val calc = (p.toDoubleOrNull() ?: 0.0) * 4 + (c.toDoubleOrNull() ?: 0.0) * 4 + (fat.toDoubleOrNull() ?: 0.0) * 9
    val k = kcal.toDoubleOrNull()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Panel {
                if (f?.source == "builtin") Dim("Built-in, approximate. If you have the package, a copy of its label is more accurate.")
                ScanButtons { r ->
                    r.onSuccess { l ->
                        l.serving?.let { serving = it }
                        l.servingGrams?.let { grams = Num.fmt(Math.round(it * 10) / 10.0) }
                        l.servingMl?.let { ml = Num.fmt(Math.round(it * 10) / 10.0) }
                        if (units.isEmpty()) for (u in l.units) units += mutableStateOf(u.name) to mutableStateOf(Num.fmt(Math.round(u.servings * 1000) / 1000.0))
                        l.kcal?.let { kcal = Num.fmt(it) }; l.protein?.let { p = Num.fmt(it) }
                        l.carbs?.let { c = Num.fmt(it) }; l.fat?.let { fat = Num.fmt(it) }
                        for ((n, v) in l.nutrients) nut.getValue(n).value = Num.fmt(Math.round(v * 10) / 10.0)
                        scanNote = when {
                            l.found == 0 -> "Couldn't find a Nutrition Facts panel in that photo. Try closer, flat, good light."
                            l.missing.isEmpty() -> "Read ${l.found} values" + (l.servingsPerContainer?.let { " (${Num.fmt(it)} servings per container)" } ?: "") + ". Check each one against the label, then add the name."
                            else -> "Read ${l.found} values; couldn't find ${l.missing.joinToString()}. Fill those in by hand and check the rest."
                        }
                    }.onFailure { scanNote = "Couldn't read the photo: ${it.message ?: it.javaClass.simpleName}" }
                }
                scanNote?.let { Text(it, color = Palette.amber) }
                LabelField("Name", name, false) { name = it }
                LabelField("Brand (optional)", brand, false) { brand = it }
                LabelField("Serving size, as printed (e.g. 2/3 cup (55 g))", serving, false) { t ->
                    serving = t
                    val ps = Units.parseServing(t)
                    if (grams.isBlank()) ps.grams?.let { grams = Num.fmt(Math.round(it * 10) / 10.0) }
                    if (ml.isBlank()) ps.ml?.let { ml = Num.fmt(Math.round(it * 10) / 10.0) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelField("Grams / serving", grams, true, Modifier.weight(1f)) { grams = it }
                    LabelField("ml / serving", ml, true, Modifier.weight(1f)) { ml = it }
                }
                Dim("Grams unlock g/oz/lb/kg; ml unlocks tsp/tbsp/fl oz/cup. Fill both if you know them.")
                SectionLabel("Per serving")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelField("Calories", kcal, true, Modifier.weight(1f)) { kcal = it }
                    LabelField("Protein g", p, true, Modifier.weight(1f)) { p = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelField("Carbs g", c, true, Modifier.weight(1f)) { c = it }
                    LabelField("Fat g", fat, true, Modifier.weight(1f)) { fat = it }
                }
                if (k != null && calc > 0 && abs(calc - k) / k > 0.15) Text("Macros add up to ${calc.roundToInt()} kcal vs ${k.roundToInt()} on the label — double-check.", color = Palette.amber)
            }
        }
        item {
            Panel {
                SectionLabel("Count units (optional)")
                Dim("Things you count: 1 egg, 1 slice, 1 scoop, 1 breast. How many servings is one?")
                for ((i, pair) in units.withIndex()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LabelField("Name", pair.first.value, false, Modifier.weight(1.3f)) { pair.first.value = it }
                    LabelField("Servings each", pair.second.value, true, Modifier.weight(1f)) { pair.second.value = it }
                    TextButton(onClick = { units.removeAt(i) }) { Text("✕") }
                }
                TextButton(onClick = { units += mutableStateOf("") to mutableStateOf("1") }) { Text("+ Add a unit") }
            }
        }
        item {
            Panel {
                SectionLabel("Rest of the label (optional)")
                for (n in Nutrient.entries) {
                    val st = nut.getValue(n)
                    LabelField("${n.label} (${n.unit})", st.value, true) { st.value = it }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Archived (hidden when adding)", Modifier.weight(1f))
                    Switch(archived, { archived = it })
                }
            }
        }
        item {
            Button(enabled = ok, onClick = {
                val food = Food(
                    id = f?.id ?: Actions.newFoodId(Graph.data, name),
                    name = name.trim(), brand = brand.trim(), serving = serving.trim().ifEmpty { "1 serving" },
                    servingGrams = grams.toDoubleOrNull()?.takeIf { it > 0 },
                    servingMl = ml.toDoubleOrNull()?.takeIf { it > 0 },
                    units = units.mapNotNull { (n, s) ->
                        val sv = s.value.toDoubleOrNull()
                        if (n.value.isBlank() || sv == null || sv <= 0) null else FoodUnit(n.value.trim().lowercase(), sv)
                    },
                    kcal = kcal.toDouble(), protein = p.toDouble(), carbs = c.toDouble(), fat = fat.toDouble(),
                    nutrients = nut.mapNotNull { (n, st) -> st.value.toDoubleOrNull()?.let { n to it } }.toMap(),
                    source = if (f?.source == "builtin" && f.kcal == kcal.toDouble() && f.protein == p.toDouble()) "builtin" else "label",
                    archived = archived,
                )
                Graph.update { Actions.saveFood(it, food) }
                onDone()
            }, modifier = Modifier.fillMaxWidth()) { Text("Save food") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun MealEditScreen(d: DietData, id: String, onDone: () -> Unit) {
    val m = d.meal(id) ?: run { Text("Deleted", Modifier.padding(16.dp)); return }
    fun save(f: (SavedMeal) -> SavedMeal) = Graph.update { d2 -> d2.meal(id)?.let { Actions.saveMeal(d2, f(it)) } ?: d2 }
    var name by remember(id) { mutableStateOf(m.name) }
    var instructions by remember(id) { mutableStateOf(m.instructions) }
    var picker by remember { mutableStateOf(false) }
    var editIdx by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val total = m.items.fold(Macros.ZERO) { acc, it -> acc + (d.food(it.foodId)?.let { f -> foodMacros(f) * it.servings } ?: Macros.ZERO) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Panel {
                OutlinedTextField(name, { name = it; if (it.isNotBlank()) save { x -> x.copy(name = it.trim()) } }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Stepper("Makes (portions)", m.servings.roundToInt().coerceAtLeast(1), 1..20) { v -> save { it.copy(servings = v.toDouble()) } }
                Text("Whole recipe: " + macroLine(total), style = Mono)
                Text("Per portion: " + macroLine(total * (1.0 / m.servings.coerceAtLeast(1.0))), style = Mono, color = Palette.accent)
            }
        }
        item { SectionLabel("Ingredients") }
        for ((i, mi) in m.items.withIndex()) item {
            val f = d.food(mi.foodId)
            Row(Modifier.fillMaxWidth().clickable { editIdx = i }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(f?.name ?: "(deleted food)")
                    Dim(Units.describe(f, mi.servings, mi.amount, mi.unit))
                }
                Text("${((f?.kcal ?: 0.0) * mi.servings).roundToInt()}", style = Mono)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker = true }) { Text("+ Add ingredient") }
                OutlinedTextField(instructions, { instructions = it; save { x -> x.copy(instructions = it) } }, label = { Text("How to make it") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { confirmDelete = true }) { Text("Delete meal", color = Palette.red) }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (picker) FoodPicker(d, onDismiss = { picker = false }) { f -> picker = false; save { it.copy(items = it.items + MealItem(f.id, 1.0)) } }
    editIdx?.let { i ->
        val item = m.items.getOrNull(i)
        val f = item?.let { d.food(it.foodId) }
        if (item == null || f == null) { editIdx = null } else AmountDialog(
            d, f, confirm = "Save", initialAmount = item.amount ?: item.servings, initialUnit = item.unit ?: Units.SERVING,
            onDismiss = { editIdx = null },
        ) { s, a, u ->
            save { x -> x.copy(items = x.items.mapIndexed { j, it -> if (j == i) it.copy(servings = s, amount = a, unit = u) else it }) }
            editIdx = null
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${m.name}?") },
        text = { Text("Days you already logged keep their food.") },
        confirmButton = { TextButton(onClick = { Graph.update { Actions.deleteMeal(it, id) }; confirmDelete = false; onDone() }) { Text("Delete", color = Palette.red) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
fun FoodPicker(d: DietData, onDismiss: () -> Unit, onPick: (Food) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = d.foods.filter { !it.archived && matches(q, it.name, it.brand) }.sortedWith(compareBy({ it.source == "builtin" }, { it.name.lowercase() }))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ingredient") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.height(320.dp)) {
                    items(list, key = { it.id }) { f -> FoodRow(f) { onPick(f) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
