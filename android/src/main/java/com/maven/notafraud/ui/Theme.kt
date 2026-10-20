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
    val green = Color(0xFF8CFF6E)
    val greenDim = Color(0xFF6FB85E)
    val amber = Color(0xFFFFC857)
    val red = Color(0xFFFF6B6B)
    val text = Color(0xFFE4EDE1)
    val textDim = Color(0xFFBCC8B8)
}

private val scheme = darkColorScheme(
    primary = Palette.green,
    onPrimary = Color(0xFF0B1A08),
    primaryContainer = Color(0xFF24401C),
    onPrimaryContainer = Palette.green,
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
fun NafTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
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
