@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maven.notafraud.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.guard.Ladder
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.ChangeKind
import com.maven.notafraud.core.model.LadderSettings
import com.maven.notafraud.core.store.Lock
import com.maven.notafraud.guard.SecureSettings
import com.maven.notafraud.sense.AppLabels

/** The brick ladder (STATE.md § 22): status, how it climbs, bedtime, Level 3 apps, the settings lock. */
@Composable
fun LadderScreen(data: AppData, now: Long) {
    val ctx = LocalContext.current
    val l = data.settings.ladder
    val lad = remember(data, now / 60_000) { Ladder.state(data, now) }
    var picking by remember { mutableStateOf<String?>(null) }
    var timeFor by remember { mutableStateOf<String?>(null) }
    val installed = remember { AppLabels.launchable(ctx) }
    fun set(new: LadderSettings, label: String) {
        Graph.update { Lock.setLadder(it, new, label, Graph.now()) }
        Graph.refresh(ctx)
    }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item { PauseBanner(data, now, showSetUp = true) }
        item {
            Panel(highlight = lad.level > 0) {
                SectionLabel("Right now")
                Text("Level ${lad.level}: ${Ladder.NAMES[lad.level]}" + if (lad.bedtime) " (bedtime)" else "", style = MaterialTheme.typography.titleLarge,
                    color = when (lad.level) { 0 -> Palette.green; 1, 2 -> Palette.amber; else -> Palette.red })
                if (lad.reasons.isNotEmpty()) Text(lad.reasons.joinToString("\n") { "· $it" }, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                if (lad.wayDown.isNotBlank()) Text("Way down: ${lad.wayDown}", style = MaterialTheme.typography.bodySmall)
                if (!l.allowlistConfirmed) Text("Level 3 starts once you've picked your Level 3 apps below. Until then the top is Level 2.", color = Palette.amber, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Panel {
                SectionLabel("How it climbs")
                Text(
                    "Points from today only: a slipped planned block (1 each, up to 3), overdue routine items (up to 2), time in blocked apps (45 min: 1, 90: 2, 150: 3), " +
                        "a must-do workout not started (1, or 2 after 6 pm). 25+ minutes of work in the last 90 minutes takes 2 off, and any running session puts it back to Normal. " +
                        "2 points: Friction. 4: Dull. 6: Brick. Protected time never goes past Friction.",
                    color = Palette.textDim, style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Friction: credits wait twice as long, \"what are you doing?\" comes back twice as often. Dull: credits wait 3×, grayscale, unlocking lands on Now. " +
                        "Brick: only your Level 3 apps open and credits freeze. Calls, Snap reply windows, emergencies, Settings and your three apps always work.",
                    color = Palette.textDim, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ladder on", Modifier.weight(1f))
                    Switch(l.enabled, { v -> Confirm.switch("The brick ladder", v) { set(l.copy(enabled = v), "Turn the brick ladder off") } })
                }
                Text("Highest level", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (v in 1..3) FilterChip(l.maxLevel == v, { Confirm.change("Highest level", "${l.maxLevel}", "$v") { set(l.copy(maxLevel = v), "Highest ladder level: $v") } }, { Text("$v · ${Ladder.NAMES[v]}") })
                }
                Text("Grayscale from", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (v in 1..4) FilterChip(l.grayscaleFrom == v, { Confirm.change("Grayscale from", lvl(l.grayscaleFrom), lvl(v)) { set(l.copy(grayscaleFrom = v), "Grayscale from ${lvl(v)}") } }, { Text(lvl(v)) })
                }
                Text("Unlocking opens Now first from", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (v in 1..4) FilterChip(l.unlockToNowFrom == v, { Confirm.change("Unlock lands on Now from", lvl(l.unlockToNowFrom), lvl(v)) { set(l.copy(unlockToNowFrom = v), "Unlock lands on Now from ${lvl(v)}") } }, { Text(lvl(v)) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Lock screen wallpaper")
                        Text("Shows the level, what to do now and today's 3. Replaces your lock screen picture; home screen untouched.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(l.lockWallpaper, { v -> Confirm.switch("Lock screen wallpaper", v) { set(l.copy(lockWallpaper = v), "Lock screen wallpaper") } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Check-in on unlock")
                        Text("While something's overdue (routine, a missed block, Twig weigh-in or meal), unlocking shows it with quick answers. Later hides it ${data.settings.checkInLaterMin} min; 3 Laters in a row add a ladder point.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(data.settings.checkInOnUnlock, { v ->
                        Confirm.switch("Check-in on unlock", v) { Graph.update { Lock.setSwitch(it, ChangeKind.CHECKIN, v, "Turn the unlock check-in off", Graph.now()) } }
                    })
                }
                Text("Home screen widget: long-press the home screen → Widgets → Not a Fraud.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Panel {
                SectionLabel("Bedtime")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bedtime bricks the phone (Level 3)", Modifier.weight(1f))
                    Switch(l.bedtimeBrick, { v -> Confirm.switch("Bedtime brick", v) { set(l.copy(bedtimeBrick = v), "Turn the bedtime brick off") } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { timeFor = "bed" }) { Text("From ${hm(l.bedMin)}") }
                    TextButton(onClick = { timeFor = "wake" }) { Text("Until ${hm(l.wakeMin)}") }
                }
                Text("Only your Level 3 apps and your bedtime extras open; credits freeze. A shorter bedtime waits for the morning.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { picking = "bed" }) { Text("Bedtime extras (${l.bedtimeExtra.size}): alarm clock, white noise…") }
            }
        }
        item {
            Panel(highlight = !l.allowlistConfirmed) {
                SectionLabel("Level 3 apps")
                Text(
                    if (l.allowlist.isEmpty()) "None yet. Pick what must keep working (maps, camera, authenticator, school…). Suggestions come from what you use and what's usually needed."
                    else l.allowlist.map { AppLabels.label(ctx, it) }.sorted().joinToString(", "),
                    color = Palette.textDim, style = MaterialTheme.typography.bodySmall,
                )
                Text("Always open anyway: phone, texts, Settings, Not a Fraud, Not a Troglodyte, Not a Twig, your focus apps. Adding apps later waits for the morning; removing applies now.",
                    color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { picking = "allow" }) { Text(if (l.allowlistConfirmed) "Change the list" else "Pick Level 3 apps") }
            }
        }
        item {
            Panel {
                SectionLabel("Settings lock")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Guard the phone's Settings pages for Not a Fraud")
                        Text("Uninstall, force stop, its accessibility switch, notification and usage access, battery limits: locked except a ${Lock.SETTINGS_WINDOW_MIN}-minute window you ask for, which opens the next morning. Turning things on always works.",
                            color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(data.settings.guardSettingsPages, { v ->
                        Confirm.switch("Guarding Not a Fraud's Settings pages", v) {
                            Graph.update { Lock.setSwitch(it, ChangeKind.SETTINGS_GUARD, v, "Stop guarding Not a Fraud's Settings pages", Graph.now()) }
                        }
                    })
                }
                if (data.settingsWindowUntil > now) Text("Settings window open until ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(data.settingsWindowUntil))}.", color = Palette.amber, style = MaterialTheme.typography.bodySmall)
                else TextButton(onClick = {
                    Graph.update { Lock.requestSettingsWindow(it, null, Graph.now()) }
                }) { Text("Ask for a settings window tomorrow morning") }
                Text("Real emergency: restarting in Safe Mode turns off every downloaded app, this one included.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            val granted = SecureSettings.granted(ctx)
            Panel {
                SectionLabel("Computer step (optional)")
                Text(
                    if (granted) "Done. The ladder can switch grayscale, and the guard switches itself back on if it's ever turned off."
                    else "One command, once, lets Not a Fraud switch grayscale and turn its own guard back on. Nothing else: no root, no access to your data. Survives updates.",
                    color = if (granted) Palette.green else Palette.textDim, style = MaterialTheme.typography.bodySmall,
                )
                if (!granted) {
                    Text("1. Settings → About phone → Software information → tap Build number 7 times.\n2. Developer options → USB debugging on.\n3. Plug into a computer with Android platform-tools and run:",
                        style = MaterialTheme.typography.bodySmall)
                    Text(SecureSettings.command(ctx), style = Mono.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize))
                    TextButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("command", SecureSettings.command(ctx)))
                    }) { Text("Copy the command") }
                    Text("No computer: the free LADB app runs the same command on the phone itself over Wireless debugging. Turn USB / Wireless debugging back off afterwards.",
                        color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    timeFor?.let { which ->
        TimeDialog(if (which == "bed") l.bedMin else l.wakeMin, onDismiss = { timeFor = null }) { m ->
            timeFor = null
            val new = if (which == "bed") l.copy(bedMin = m) else l.copy(wakeMin = m)
            Confirm.change(if (which == "bed") "Bedtime starts" else "Bedtime ends", hm(if (which == "bed") l.bedMin else l.wakeMin), hm(m)) {
                set(new, "Bedtime ${hm(new.bedMin)}–${hm(new.wakeMin)}")
            }
        }
    }

    picking?.let { which ->
        val sugg = remember { Ladder.suggestions(data, installed.map { it.pkg }.toSet(), Graph.today()) }
        LadderPicker(
            title = if (which == "allow") "Level 3 apps" else "Bedtime extras",
            apps = installed,
            suggestions = sugg,
            blocked = data.rules.blocked,
            selected = if (which == "allow") l.allowlist else l.bedtimeExtra,
            onDismiss = { picking = null },
        ) { chosen ->
            picking = null
            if (which == "allow") {
                // First time: setting up the list is part of switching Level 3 on, so it applies now.
                if (!l.allowlistConfirmed) Graph.update { it.copy(settings = it.settings.copy(ladder = l.copy(allowlist = chosen, allowlistConfirmed = true))) }
                else set(l.copy(allowlist = chosen), "Allow at Level 3: " + (chosen - l.allowlist).joinToString { AppLabels.label(ctx, it) })
            } else set(l.copy(bedtimeExtra = chosen), "Allow at bedtime: " + (chosen - l.bedtimeExtra).joinToString { AppLabels.label(ctx, it) })
            Graph.refresh(ctx)
        }
    }
}

private fun lvl(v: Int) = if (v >= 4) "never" else "Level $v"

@Composable
private fun LadderPicker(
    title: String,
    apps: List<AppLabels.Installed>,
    suggestions: List<Pair<String, String>>,
    blocked: Set<String>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    var chosen by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    val why = suggestions.toMap()
    val order = suggestions.map { it.first }
    val list = apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        .sortedWith(compareByDescending<AppLabels.Installed> { it.pkg in selected }
            .thenBy { order.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenBy { it.label.lowercase() })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = { if (chosen == selected) onDismiss() else Confirm.ask("Save ${chosen.size} apps as your ${title.lowercase()}?") { onSave(chosen) } }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column {
                Text("Suggested first. Blocked apps are marked; you choose every app yourself.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.pkg }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = if (app.pkg in chosen) chosen - app.pkg else chosen + app.pkg },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = app.pkg in chosen, onCheckedChange = null)
                            Column {
                                Text(app.label)
                                val note = when {
                                    app.pkg in blocked -> "blocked app"
                                    else -> why[app.pkg]
                                }
                                if (note != null) Text(note, color = if (app.pkg in blocked) Palette.red else Palette.textDim, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
    )
}
