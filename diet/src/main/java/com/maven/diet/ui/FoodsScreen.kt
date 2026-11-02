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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maven.diet.Graph
import com.maven.diet.core.Actions
import com.maven.diet.core.Diary
import com.maven.diet.core.DietData
import com.maven.diet.core.Food
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
        ServingsDialog(f, onDismiss = { picked = null }) { servings ->
            Graph.update { Actions.logFood(it, day, slot, f.id, servings, logTime(day)) }
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

/** Servings or grams; shows what that adds up to. */
@Composable
fun ServingsDialog(f: Food, confirm: String = "Log", initial: Double = 1.0, onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var servings by remember { mutableStateOf(Num.fmt(initial)) }
    var grams by remember { mutableStateOf(f.servingGrams?.let { Num.fmt(it * initial) } ?: "") }
    val s = servings.toDoubleOrNull() ?: 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Dim("1 serving = ${f.serving}" + if (f.source == "builtin") " · approximate values" else "")
                ChipRow(listOf(0.5, 1.0, 1.5, 2.0, 3.0), { abs(it - s) < 1e-9 }, { Num.fmt(it) }) { v ->
                    servings = Num.fmt(v); f.servingGrams?.let { grams = Num.fmt(it * v) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(servings, { t -> servings = t; t.toDoubleOrNull()?.let { v -> f.servingGrams?.let { grams = Num.fmt(it * v) } } },
                        label = { Text("Servings") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    val sg = f.servingGrams
                    if (sg != null) OutlinedTextField(grams, { t -> grams = t; t.toDoubleOrNull()?.let { g -> servings = Num.fmt(g / sg) } },
                        label = { Text("Grams") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Text(macroLine(foodMacros(f) * s), style = Mono)
            }
        },
        confirmButton = { TextButton(enabled = s > 0, onClick = { onSave(s) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
                LabelField("Name", name, false) { name = it }
                LabelField("Brand (optional)", brand, false) { brand = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelField("Serving size, as printed", serving, false, Modifier.weight(1.4f)) { serving = it }
                    LabelField("Grams", grams, true, Modifier.weight(1f)) { grams = it }
                }
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
                    servingGrams = grams.toDoubleOrNull(),
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
                    Dim("${Num.fmt(mi.servings)} × ${f?.serving ?: ""}")
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
        if (item == null || f == null) { editIdx = null } else ServingsDialog(f, confirm = "Save", initial = item.servings, onDismiss = { editIdx = null }) { s ->
            save { x -> x.copy(items = x.items.mapIndexed { j, it -> if (j == i) it.copy(servings = s) else it }) }
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
