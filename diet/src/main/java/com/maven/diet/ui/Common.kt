package com.maven.diet.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maven.diet.core.Num

/** Same look as the other "Not a" apps, with a cyan accent. */
object Palette {
    val bg = Color(0xFF0B0F10)
    val surface = Color(0xFF141A1C)
    val surfaceHigh = Color(0xFF1D2528)
    val accent = Color(0xFF6EE7FF)
    val accentDim = Color(0xFF4FA9BD)
    val green = Color(0xFF8CFF6E)
    val amber = Color(0xFFFFC857)
    val red = Color(0xFFFF6B6B)
    val text = Color(0xFFE2ECEE)
    val textDim = Color(0xFFB4C4C8)
}

private val scheme = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Color(0xFF00222B),
    primaryContainer = Palette.accent.copy(alpha = 0.22f).compositeOver(Palette.bg),
    onPrimaryContainer = Palette.accent,
    secondary = Palette.green,
    onSecondary = Color(0xFF0B1A08),
    background = Palette.bg,
    onBackground = Palette.text,
    surface = Palette.surface,
    onSurface = Palette.text,
    surfaceVariant = Palette.surfaceHigh,
    onSurfaceVariant = Palette.textDim,
    surfaceContainer = Palette.surface,
    surfaceContainerHigh = Palette.surfaceHigh,
    surfaceContainerHighest = Color(0xFF263033),
    outline = Color(0xFF55676C),
    error = Palette.red,
)

private val mono = FontFamily.Monospace
val Mono = TextStyle(fontFamily = mono)

private val typography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontFamily = mono, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(fontFamily = mono, letterSpacing = 1.sp),
    )
}

@Composable
fun DietTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, highlight: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

@Composable
fun Dim(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

/**
 * Compact number box for set rows. Keeps the typed text locally (so "22." survives) and pushes parsed values up.
 * Empty shows [placeholder] (the program's target) in a dim colour.
 */
@Composable
fun NumBox(
    value: Double?, placeholder: String, onValue: (Double?) -> Unit,
    width: Dp, decimals: Boolean = true, enabled: Boolean = true, done: Boolean = false,
) {
    var text by remember { mutableStateOf(Num.fmt(value)) }
    LaunchedEffect(value) { if (text.toDoubleOrNull() != value) text = Num.fmt(value) }
    val border = if (done) Palette.green.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline
    Box(
        Modifier.width(width).height(40.dp)
            .border(BorderStroke(1.dp, border), RoundedCornerShape(8.dp))
            .background(if (done) Palette.green.copy(alpha = 0.08f) else Color.Transparent, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (text.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), style = Mono.copy(fontSize = 15.sp), textAlign = TextAlign.Center)
        BasicTextField(
            value = text,
            enabled = enabled,
            onValueChange = { t ->
                val clean = t.filter { it.isDigit() || (decimals && it == '.') }.take(7)
                text = clean
                onValue(clean.toDoubleOrNull())
            },
            singleLine = true,
            textStyle = Mono.copy(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Palette.accent),
            keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipRow(options: List<T>, selected: (T) -> Boolean, label: (T) -> String, onClick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        for (o in options) FilterChip(selected = selected(o), onClick = { onClick(o) }, label = { Text(label(o)) })
    }
}

@Composable
fun Stepper(label: String, value: Int, range: IntRange, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        androidx.compose.material3.OutlinedButton(onClick = { if (value > range.first) onChange((value - step).coerceAtLeast(range.first)) }) { Text("−") }
        Text("$value", style = Mono.copy(fontSize = 18.sp), modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
        androidx.compose.material3.OutlinedButton(onClick = { if (value < range.last) onChange((value + step).coerceAtMost(range.last)) }) { Text("+") }
    }
}
