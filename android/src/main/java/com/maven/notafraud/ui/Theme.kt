package com.maven.notafraud.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Terminal green on near-black: a nod to the Pip-Boy the stat layer is modeled on, kept readable. */
object Palette {
    val bg = Color(0xFF0D110D)
    val surface = Color(0xFF151B15)
    val surfaceHigh = Color(0xFF1E261E)
    /** The accent ("green" by default; Setup → Look). Backed by state so a change repaints everything. */
    var green by androidx.compose.runtime.mutableStateOf(Color(0xFF8CFF6E))
    var greenDim by androidx.compose.runtime.mutableStateOf(Color(0xFF6FB85E))
    val amber = Color(0xFFFFC857)
    val red = Color(0xFFFF6B6B)
    var text by androidx.compose.runtime.mutableStateOf(Color(0xFFE4EDE1))
    /** Secondary text; brighter with Look → Text contrast. */
    var textDim by androidx.compose.runtime.mutableStateOf(Color(0xFFBCC8B8))
}

/** Accent name -> (bright, dim). */
val ACCENTS = linkedMapOf(
    "green" to (Color(0xFF8CFF6E) to Color(0xFF6FB85E)),
    "cyan" to (Color(0xFF6EE7FF) to Color(0xFF4FA9BD)),
    "violet" to (Color(0xFFC3A8FF) to Color(0xFF8E7BC4)),
    "pink" to (Color(0xFFFF8AD8) to Color(0xFFC46AA6)),
    "orange" to (Color(0xFFFFA65C) to Color(0xFFC07C42)),
    "white" to (Color(0xFFF2F2F2) to Color(0xFFB4B4B4)),
)

private fun scheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Color(0xFF0B1A08),
    primaryContainer = accent.copy(alpha = 0.22f).compositeOver(Palette.bg),
    onPrimaryContainer = accent,
    secondary = Palette.amber,
    onSecondary = Color(0xFF231A00),
    background = Palette.bg,
    onBackground = Palette.text,
    surface = Palette.surface,
    onSurface = Palette.text,
    surfaceVariant = Palette.surfaceHigh,
    onSurfaceVariant = Palette.textDim,
    surfaceContainer = Palette.surface,
    surfaceContainerHigh = Palette.surfaceHigh,
    surfaceContainerHighest = Color(0xFF263026),
    outline = Color(0xFF5A6E57),
    error = Palette.red,
)

private val mono = FontFamily.Monospace

private val typography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontFamily = mono, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(fontFamily = mono, letterSpacing = 1.sp),
    )
}

@Composable
fun NafTheme(accent: String = "green", contrast: Int = 0, content: @Composable () -> Unit) {
    val (bright, dim) = ACCENTS[accent] ?: ACCENTS.getValue("green")
    Palette.green = bright
    Palette.greenDim = dim
    Palette.textDim = when (contrast) { 0 -> Color(0xFFBCC8B8); 1 -> Color(0xFFD8E2D4); else -> Color(0xFFF0F5EE) }
    Palette.text = if (contrast >= 2) Color(0xFFFFFFFF) else Color(0xFFE4EDE1)
    val cs = androidx.compose.runtime.remember(bright) { scheme(bright) }
    MaterialTheme(colorScheme = cs, typography = typography, content = content)
}

val Mono = TextStyle(fontFamily = mono)

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
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
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
