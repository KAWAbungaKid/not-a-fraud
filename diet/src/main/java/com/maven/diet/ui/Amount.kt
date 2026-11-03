package com.maven.diet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.maven.diet.core.DietData
import com.maven.diet.core.Food
import com.maven.diet.core.Num
import com.maven.diet.core.UnitOption
import com.maven.diet.core.Units
import kotlin.math.abs

/** The unit you used last time for this food (so chicken opens in oz if that's how you log it). */
fun lastUnit(d: DietData, foodId: String): Pair<Double, String>? =
    d.log.filter { it.foodId == foodId && it.unit != null && it.amount != null }.maxByOrNull { it.at }?.let { it.amount!! to it.unit!! }

/**
 * How much: an amount in any unit the food supports — servings, its count units (egg, slice, scoop),
 * g / oz / lb / kg when grams are known, ml / tsp / tbsp / fl oz / cup / pint / l when volume is known.
 */
@Composable
fun AmountDialog(
    d: DietData, f: Food, confirm: String = "Log",
    initialAmount: Double? = null, initialUnit: String? = null,
    onDismiss: () -> Unit, onSave: (servings: Double, amount: Double, unit: String) -> Unit,
) {
    val options = remember(f) { Units.options(f) }
    val start = remember(f) {
        when {
            initialAmount != null -> initialAmount to (initialUnit ?: Units.SERVING)
            else -> lastUnit(d, f.id) ?: (1.0 to Units.default(f).name)
        }
    }
    var unit by remember { mutableStateOf(options.firstOrNull { it.name == start.second } ?: options.first()) }
    var amount by remember { mutableStateOf(Num.fmt(start.first)) }
    val a = amount.toDoubleOrNull() ?: 0.0
    val servings = a * unit.servingsPerUnit
    val quick = when (unit.kind) {
        UnitOption.Kind.MASS -> when (unit.name) { "g" -> listOf(50.0, 100.0, 150.0, 200.0); "oz" -> listOf(2.0, 4.0, 6.0, 8.0); else -> listOf(0.25, 0.5, 1.0) }
        UnitOption.Kind.VOLUME -> when (unit.name) { "ml" -> listOf(100.0, 250.0, 500.0); "cup" -> listOf(0.25, 0.5, 1.0, 1.5, 2.0); else -> listOf(0.5, 1.0, 2.0, 3.0) }
        else -> listOf(0.5, 1.0, 1.5, 2.0, 3.0)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Dim("1 serving = ${f.serving}" + if (f.source == "builtin") " · approximate values" else "")
                OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(7) },
                    label = { Text("Amount (${unit.name})") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                ChipRow(quick, { abs(it - a) < 1e-9 }, { Num.fmt(it) }) { amount = Num.fmt(it) }
                SectionLabel("Unit")
                ChipRow(options, { it.name == unit.name }, { it.name }) { u ->
                    // Keep the same quantity of food when switching units.
                    if (a > 0) amount = Num.fmt(Math.round(servings / u.servingsPerUnit * 100) / 100.0)
                    unit = u
                }
                if (f.servingGrams == null && f.servingMl == null) Dim("Add grams or ml to this food (Foods → edit) to log it by weight or volume.")
                Text(macroLine(foodMacros(f) * servings), style = Mono)
                if (unit.name != Units.SERVING) Dim("= ${Num.fmt(Math.round(servings * 100) / 100.0)} servings")
            }
        },
        confirmButton = { TextButton(enabled = servings > 0, onClick = { onSave(servings, a, unit.name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
