package com.maven.notafraud.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.AppData

/** Every page. "page" ones can sit in the bottom bar; "setting" ones live under More. */
data class PageDef(val id: String, val label: String, val icon: ImageVector, val setting: Boolean, val blurb: String)

val PAGES = listOf(
    PageDef("now", "Now", Icons.Filled.PlayArrow, false, "What to do right now, today's 3"),
    PageDef("plan", "Plan", Icons.Filled.ViewTimeline, false, "Today and the next 6 days"),
    PageDef("tasks", "Tasks", Icons.Filled.AccountTree, false, "Map, projects and to-do list"),
    PageDef("routine", "Routine", Icons.Filled.Restaurant, false, "Daily habits that get angry"),
    PageDef("stats", "Stats", Icons.Filled.Insights, false, "Scores from sleep, workouts, focus"),
    PageDef("skills", "Skills", Icons.Filled.AutoAwesome, false, "Skill constellations"),
    PageDef("classes", "Classes", Icons.Filled.School, false, "Semester dates and class times"),
    PageDef("setup", "Setup checks", Icons.Filled.Checklist, true, "Permissions the app needs"),
    PageDef("blocking", "Blocking & credits", Icons.Filled.Shield, true, "Blocked apps and sites, rules, why wasn't I blocked"),
    PageDef("ladder", "Brick ladder", Icons.Filled.Lock, true, "Gets stricter as the day drifts; bedtime; Level 3 apps; settings lock"),
    PageDef("calendar", "Calendar & health", Icons.Filled.CalendarMonth, true, "Google Calendar, Samsung Health sleep"),
    PageDef("blocks", "Weekly blocks", Icons.Filled.Event, true, "Trading, craft blocks, protected time"),
    PageDef("planning", "Planning", Icons.Filled.Tune, true, "Re-planning, stats, recharge ideas"),
    PageDef("projects", "Projects", Icons.Filled.Folder, true, "Archive and restore projects"),
    PageDef("look", "Look & tabs", Icons.Filled.Palette, true, "Accent, bottom bar, Now cards"),
    PageDef("data", "Data & about", Icons.Filled.Storage, true, "Backup, restore, version"),
)

fun page(id: String) = PAGES.firstOrNull { it.id == id }

/** Bottom-bar pages from settings (at most 4; More is always added). */
fun tabsOf(d: AppData): List<PageDef> = d.settings.tabs.mapNotNull { page(it) }.filter { !it.setting }.distinctBy { it.id }.take(4).ifEmpty { listOf(PAGES.first()) }

@Composable
fun MoreScreen(data: AppData, onOpen: (String) -> Unit) {
    val inBar = tabsOf(data).map { it.id }.toSet()
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        val pages = PAGES.filter { !it.setting && it.id !in inBar }
        if (pages.isNotEmpty()) {
            item { SectionLabel("Pages") }
            items(pages, key = { it.id }) { p -> MoreRow(p) { onOpen(p.id) } }
        }
        item { SectionLabel("Settings") }
        items(PAGES.filter { it.setting }, key = { it.id }) { p -> MoreRow(p) { onOpen(p.id) } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun MoreRow(p: PageDef, onClick: () -> Unit) {
    Panel(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(p.icon, null, tint = Palette.green)
            Spacer(Modifier.width(14.dp))
            androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                Text(p.label, style = MaterialTheme.typography.titleMedium)
                Text(p.blurb, style = MaterialTheme.typography.bodySmall, color = Palette.textDim)
            }
        }
    }
}

val NOW_CARDS = listOf(
    "pick3" to "Today's / tomorrow's 3",
    "numbers" to "Credits · sleep · focus · phone",
    "moved" to "Moved off today / won't fit this week",
    "homework" to "Homework: how far ahead you are",
    "workout" to "Workouts: this week's minimum",
    "today" to "How's today?",
    "upnext" to "Up next (top tasks)",
)

@Composable
fun LookControls(data: AppData) {
    Panel {
        Text("Text contrast", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val names = listOf("Normal", "Brighter", "Brightest")
            for (i in 0..2) androidx.compose.material3.FilterChip(data.settings.textContrast == i, {
                Confirm.change("Text contrast", names[data.settings.textContrast.coerceIn(0, 2)], names[i]) { Graph.update { it.copy(settings = it.settings.copy(textContrast = i)) } }
            }, { Text(names[i]) })
        }
        Text("Secondary text, like this line.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
    }
    Panel {
        Text("Bottom bar (up to 4, More is always there)", style = MaterialTheme.typography.titleSmall)
        val tabs = tabsOf(data).map { it.id }
        for (p in PAGES.filter { !it.setting }) {
            val on = p.id in tabs
            Row(Modifier.fillMaxWidth().clickable(enabled = on || tabs.size < 4) {
                val next = if (on) tabs - p.id else tabs + p.id
                if (next.isNotEmpty()) Graph.update { it.copy(settings = it.settings.copy(tabs = PAGES.map { x -> x.id }.filter { x -> x in next })) }
            }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, null, enabled = on || tabs.size < 4)
                Icon(p.icon, null, tint = if (on) Palette.green else Palette.textDim)
                Spacer(Modifier.width(10.dp))
                Text(p.label)
            }
        }
        Text("Pages not in the bar are one tap away under More.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
    }
    Panel {
        Text("Cards on Now", style = MaterialTheme.typography.titleSmall)
        for ((key, label) in NOW_CARDS) {
            val on = key !in data.settings.nowHidden
            Row(Modifier.fillMaxWidth().clickable {
                Graph.update { it.copy(settings = it.settings.copy(nowHidden = if (on) it.settings.nowHidden + key else it.settings.nowHidden - key)) }
            }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, null)
                Text(label)
            }
        }
    }
    Panel {
        Text("Accent color", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((name, c) in ACCENTS) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(36.dp).clip(CircleShape).background(c.first)
                        .clickable { Graph.update { it.copy(settings = it.settings.copy(accent = name)) } },
                    contentAlignment = Alignment.Center,
                ) { if (data.settings.accent == name) Text("✓", color = Palette.bg) }
            }
        }
    }
}
