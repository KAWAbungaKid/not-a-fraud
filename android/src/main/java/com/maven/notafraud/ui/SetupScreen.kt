package com.maven.notafraud.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import com.maven.notafraud.BuildConfig
import com.maven.notafraud.Graph
import com.maven.notafraud.core.ics.IcsParser
import com.maven.notafraud.core.model.Anchor
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.Project
import com.maven.notafraud.core.model.ProjectKind
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.store.Actions
import com.maven.notafraud.core.store.WebImport
import com.maven.notafraud.sense.AppLabels
import com.maven.notafraud.sense.Health
import com.maven.notafraud.sense.Perms
import com.maven.notafraud.work.Workers
import kotlinx.coroutines.launch
import java.io.File

private enum class Picker { BLOCKED, FOCUS, MIRRORED }

@Composable
fun SetupScreen(data: AppData, resumes: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<Picker?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    val p = remember(resumes) { PermSnapshot.read(ctx) }
    val healthGranted by produceState(false, resumes) { value = runCatching { Health.granted(ctx) }.getOrDefault(false) }
    val healthStatus = remember(resumes) { HealthConnectClient.getSdkStatus(ctx) }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Graph.refresh(ctx) }
    var editingAnchor by remember { mutableStateOf<Anchor?>(null) }
    var editingSites by remember { mutableStateOf(false) }
    var editingResearch by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<String?>(null) }
    val calLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch { Workers.syncSensors(ctx); Graph.replan(); Graph.refresh(ctx) }
    }
    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch { Workers.syncSensors(ctx) }
    }
    val icsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = readText(ctx, uri) ?: return@rememberLauncherForActivityResult
        val events = IcsParser.parse(text, Graph.zone)
        Graph.changeAndReplan(ctx) { Actions.mergeIcs(it, events) }
        toast = "Imported ${events.size} calendar events."
    }
    val webLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = readText(ctx, uri) ?: return@rememberLauncherForActivityResult
        toast = runCatching {
            var result: WebImport.Result? = null
            Graph.changeAndReplan(ctx) { d -> WebImport.merge(d, text).also { result = it }.data }
            result!!.let { "Imported ${it.tasks} tasks, ${it.projects} projects, ${it.events} events, ${it.sessions} sessions." }
        }.getOrElse { "Couldn't read that file: ${it.message}" }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = readText(ctx, uri) ?: return@rememberLauncherForActivityResult
        toast = Graph.store.importJson(text).fold({ Graph.replan(); Graph.refresh(ctx); "Backup restored." }, { "Not a valid backup: ${it.message}" })
    }

    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.height(8.dp)) }
        item {
            val done = listOf(p.notifications, p.accessibility, p.usage, p.listener, healthGranted, p.calendar, p.exact, p.battery).count { it }
            Text("Setup · $done/8 done", style = MaterialTheme.typography.titleLarge)
            Text("Go top to bottom. Each one is a Settings screen only you can tap through.", color = Palette.textDim)
        }

        item {
            PermRow("1. Notifications", p.notifications, "The \"do this now\" line and nudges.") {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        item {
            PermRow(
                "2. Guard (Accessibility)", p.accessibility,
                "Blocks time-sink apps and earns credits from focus apps. If the switch is greyed out: App info → ⋮ → Allow restricted settings, then come back.",
                extra = { TextButton(onClick = { openAppInfo(ctx) }) { Text("App info") } },
            ) { open(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        item {
            PermRow("3. Usage access", p.usage, "Per-app screen time.") {
                open(ctx, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
        }
        item {
            PermRow("4. Reply windows (Notification access)", p.listener, "Tapping a Snapchat notification opens it free for a few minutes, so Snapchat can stay blocked otherwise. Same restricted-settings note as the guard.") {
                open(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        item {
            val detail = when (healthStatus) {
                HealthConnectClient.SDK_AVAILABLE -> "Sleep, workouts and steps from Samsung Health. In Samsung Health: Settings → Health Connect → allow it to write sleep and exercise."
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect needs an update from the Play Store first."
                else -> "Health Connect isn't available on this device."
            }
            PermRow("5. Health Connect", healthGranted, detail) {
                if (healthStatus == HealthConnectClient.SDK_AVAILABLE) healthLauncher.launch(Health.PERMISSIONS)
                else open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata")))
            }
        }
        item {
            PermRow("6. Calendar", p.calendar && Perms.calendarWrite(ctx), "Reads your Google Calendar (through the phone) so events block time, and writes your plan into a \"Not a Fraud\" calendar.") {
                calLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
            }
        }
        item {
            PermRow("7. Exact alarms", p.exact, "Flips the notification right at each block boundary.") {
                open(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
            }
        }
        item {
            PermRow("8. Battery: unrestricted", p.battery, "Without this, One UI kills the guard after a few hours.") {
                open(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
            }
        }
        item {
            Panel {
                Text("9. Samsung: never sleeping", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Settings → Battery → Background usage limits: remove Not a Fraud from Sleeping apps and Deep sleeping apps, add it to Never sleeping apps, and turn off \"Put unused apps to sleep\". This can't be checked from here.",
                    color = Palette.textDim, style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { openAppInfo(ctx) }) { Text("Open app info") }
            }
        }

        item { SectionLabel("Blocking") }
        item {
            Panel {
                SwitchRow("Blocking on", data.rules.blockingEnabled) { on -> Graph.update { it.copy(rules = it.rules.copy(blockingEnabled = on)) } }
                SwitchRow("Nudges on", data.settings.nagsEnabled) { on -> Graph.update { it.copy(settings = it.settings.copy(nagsEnabled = on)) } }
                SwitchRow("Routine nudges on", data.settings.routineNagsEnabled) { on -> Graph.update { it.copy(settings = it.settings.copy(routineNagsEnabled = on)) } }
                SwitchRow("Bedtime nudges make a sound (through Do Not Disturb)", data.settings.bedtimeSound) { on -> Graph.update { it.copy(settings = it.settings.copy(bedtimeSound = on)) } }
                TextButton(onClick = { picker = Picker.BLOCKED }) { Text("Blocked apps (${data.rules.blocked.size})") }
                TextButton(onClick = { editingSites = true }) { Text("Blocked websites (${data.rules.blockedSites.size})") }
                TextButton(onClick = { editingProfile = "com.snapchat.android" }) { Text("Snapchat rules (reply window, Quick Snap, typing)") }
                TextButton(onClick = { editingProfile = "" }) { Text("Rules for every other blocked app") }
                Text("Quick Snap: pull down twice → pencil/edit → drag the \"Quick Snap\" tile into your quick settings.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { picker = Picker.FOCUS }) { Text("Focus apps that earn credits (${data.rules.focus.size})") }
                            }
        }

        item { SectionLabel("Credits") }
        item {
            Panel {
                Text("${data.ledger.balanceMin.toInt()} min banked · max ${data.settings.maxBalanceMin.toInt()}", style = MaterialTheme.typography.titleMedium)
                Text("1 focus-app minute or 1 logged task minute = 1 credit minute.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                data.ledger.entries.takeLast(8).reversed().forEach { e ->
                    Row {
                        Text(e.reason, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text("%+.0f".format(e.deltaMin), style = Mono, color = if (e.deltaMin >= 0) Palette.green else Palette.amber)
                    }
                }
                var phrase by remember(data.settings.emergencyPhrase) { mutableStateOf(data.settings.emergencyPhrase) }
                OutlinedTextField(
                    phrase, { phrase = it }, label = { Text("Emergency unlock phrase") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                var creed by remember(data.settings.creed) { mutableStateOf(data.settings.creed) }
                OutlinedTextField(creed, { creed = it }, label = { Text("Creed (shown on the block screen)") }, modifier = Modifier.fillMaxWidth())
                TextButton(
                    onClick = { Graph.update { it.copy(settings = it.settings.copy(emergencyPhrase = phrase.trim(), creed = creed.trim())) } },
                    enabled = phrase.isNotBlank() && (phrase != data.settings.emergencyPhrase || creed != data.settings.creed),
                ) { Text("Save") }
            }
        }

        item { SectionLabel("Why wasn't I blocked?") }
        item { GuardLogPanel() }

        item { SectionLabel("Planning") }
        item {
            var ideas by remember { mutableStateOf(false) }
            Panel {
                SwitchRow("Re-plan when a block slips by", data.settings.autoReplan) { on -> Graph.update { it.copy(settings = it.settings.copy(autoReplan = on)) } }
                SwitchRow("Low stats raise their areas' importance", data.settings.statsDriveImportance) { on -> Graph.changeAndReplan(ctx) { it.copy(settings = it.settings.copy(statsDriveImportance = on)) } }
                TextButton(onClick = { ideas = true }) { Text("Recharge ideas: " + data.settings.rechargeIdeas.joinToString(", ").ifBlank { "none" }) }
            }
            if (ideas) ListDialog("Recharge ideas", "One per line. Recharge blocks suggest these.", data.settings.rechargeIdeas, { ideas = false }) { l ->
                Graph.changeAndReplan(ctx) { it.copy(settings = it.settings.copy(rechargeIdeas = l)) }; ideas = false
            }
        }

        item { SectionLabel("Look") }
        item {
            Panel {
                Text("Accent color", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((name, c) in ACCENTS) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(c.first)
                                .then(if (data.settings.accent == name) Modifier.background(c.first) else Modifier)
                                .clickable { Graph.update { it.copy(settings = it.settings.copy(accent = name)) } },
                            contentAlignment = Alignment.Center,
                        ) { if (data.settings.accent == name) Text("✓", color = Palette.bg) }
                    }
                }
            }
        }

        item { SectionLabel("Google Calendar") }
        item { CalendarPanel(data, resumes) }

        item { SectionLabel("Weekly blocks") }
        item {
            Text("Hard never moves. Habit keeps its slot unless a deadline is hot (if allowed). Container gets filled with your hottest project work. Meals, cardio and workouts are in Routine.",
                color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
        }
        val recurring = data.anchors.filter { it.dates.isEmpty() }.sortedWith(compareBy({ it.days.minOrNull() ?: 8 }, { it.startMin }))
        items(recurring, key = { it.id }) { a -> AnchorRow(a) { editingAnchor = a } }
        item {
            TextButton(onClick = {
                editingAnchor = Anchor(title = "", kind = com.maven.notafraud.core.model.AnchorKind.ROUTINE, startMin = 9 * 60, endMin = 10 * 60, days = setOf(1),
                    mode = com.maven.notafraud.core.model.AnchorMode.HABIT)
            }) { Text("Add weekly block") }
        }
        item {
            val dated = data.anchors.filter { it.dates.isNotEmpty() }
            val days = dated.flatMap { it.dates }.distinct().sorted()
            Panel(Modifier.clickable { editingResearch = true }) {
                Text("Research session days", style = MaterialTheme.typography.titleSmall)
                Text(days.filter { it >= Graph.today().toString() }.joinToString(", ") { it.substring(5) }.ifBlank { "none upcoming" }, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                Text("Tap to edit", color = Palette.textDim, style = MaterialTheme.typography.labelSmall)
            }
        }

        item { SectionLabel("Projects") }
        item { ProjectsPanel(data) }

        item { SectionLabel("Data") }
        item {
            Panel {
                TextButton(onClick = { icsLauncher.launch(arrayOf("text/calendar", "text/*", "*/*")) }) { Text("Import .ics calendar file") }
                TextButton(onClick = { webLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }) { Text("Import from Project Max web app (.json)") }
                TextButton(onClick = { shareBackup(ctx) }) { Text("Export backup") }
                TextButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "*/*")) }) { Text("Restore backup (replaces everything)") }
                TextButton(onClick = { scope.launch { Workers.syncSensors(ctx); toast = "Synced." } }) { Text("Sync sensors now") }
                toast?.let { Text(it, color = Palette.green, style = MaterialTheme.typography.bodySmall) }
            }
        }

        item { SectionLabel("About") }
        item {
            Panel {
                Text("Not a Fraud ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/KAWAbungaKid/not-a-fraud/releases"))) }) {
                    Text("Check for updates")
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }

    editingAnchor?.let { a ->
        AnchorEditor(a, data, isNew = data.anchors.none { it.id == a.id }, onDismiss = { editingAnchor = null },
            onSave = { Graph.changeAndReplan(ctx) { d -> com.maven.notafraud.core.store.Actions.upsertAnchor(d, it) }; editingAnchor = null },
            onDelete = { Graph.changeAndReplan(ctx) { d -> com.maven.notafraud.core.store.Actions.deleteAnchor(d, a.id) }; editingAnchor = null })
    }
    editingProfile?.let { pkg ->
        val current = if (pkg.isEmpty()) data.rules.defaultProfile else data.rules.profile(pkg)
        ProfileEditor(if (pkg.isEmpty()) "Other blocked apps" else com.maven.notafraud.sense.AppLabels.label(ctx, pkg), current, onDismiss = { editingProfile = null }) { p ->
            Graph.update {
                if (pkg.isEmpty()) it.copy(rules = it.rules.copy(defaultProfile = p))
                else it.copy(rules = it.rules.copy(profiles = it.rules.profiles + (pkg to p)))
            }
            editingProfile = null
        }
    }
    if (editingSites) {
        ListDialog("Blocked websites", "One per line, e.g. instagram.com. Subdomains are included.", data.rules.blockedSites.sorted(), onDismiss = { editingSites = false }) { list ->
            val clean = list.map { it.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('/') }.filter { it.contains('.') }.toSet()
            Graph.update { it.copy(rules = it.rules.copy(blockedSites = clean)) }
            editingSites = false
        }
    }
    if (editingResearch) {
        val days = data.anchors.filter { it.dates.isNotEmpty() }.flatMap { it.dates }.distinct().sorted()
        ListDialog("Research session days", "One date per line, YYYY-MM-DD.", days, onDismiss = { editingResearch = false }) { list ->
            val clean = list.map { it.trim() }.filter { runCatching { java.time.LocalDate.parse(it) }.isSuccess }
            Graph.changeAndReplan(ctx) { com.maven.notafraud.core.store.Actions.setResearchDates(it, clean) }
            editingResearch = false
        }
    }

    picker?.let { which ->
        val current = when (which) {
            Picker.BLOCKED -> data.rules.blocked
            Picker.FOCUS -> data.rules.focus
            Picker.MIRRORED -> data.rules.mirrored
        }
        AppPicker(
            title = when (which) { Picker.BLOCKED -> "Blocked apps"; Picker.FOCUS -> "Focus apps"; Picker.MIRRORED -> "Mirror into Inbox" },
            selected = current,
            onDismiss = { picker = null },
            onSave = { set ->
                Graph.update {
                    when (which) {
                        Picker.BLOCKED -> it.copy(rules = it.rules.copy(blocked = set, focus = it.rules.focus - set))
                        Picker.FOCUS -> it.copy(rules = it.rules.copy(focus = set, blocked = it.rules.blocked - set))
                        Picker.MIRRORED -> it.copy(rules = it.rules.copy(mirrored = set))
                    }
                }
                picker = null
            },
        )
    }
}

private data class PermSnapshot(
    val notifications: Boolean, val accessibility: Boolean, val usage: Boolean, val listener: Boolean,
    val calendar: Boolean, val exact: Boolean, val battery: Boolean,
) {
    companion object {
        fun read(ctx: Context) = PermSnapshot(
            Perms.notifications(ctx), Perms.accessibility(ctx), Perms.usageAccess(ctx), Perms.notificationListener(ctx),
            Perms.calendar(ctx), Perms.exactAlarms(ctx), Perms.batteryUnrestricted(ctx),
        )
    }
}

@Composable
private fun PermRow(title: String, ok: Boolean, detail: String, extra: (@Composable () -> Unit)? = null, onFix: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) Palette.green else Palette.amber))
            Spacer(Modifier.size(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (!ok) TextButton(onClick = onFix) { Text("Fix") } else Text("On", color = Palette.green)
        }
        if (!ok) {
            Text(detail, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
            extra?.invoke()
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

private val DAY_NAMES = listOf("", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
private fun AnchorRow(a: Anchor, onEdit: () -> Unit) {
    val ctx = LocalContext.current
    Panel(Modifier.clickable(onClick = onEdit)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.title)
                Text(
                    a.days.sorted().joinToString(" ") { DAY_NAMES[it] } + " · " + hm(a.startMin) + "–" + hm(a.endMin) + " · " + a.mode.name.lowercase() +
                        (if (a.protected) " · protected" else "") + (if (a.yieldsToHot) " · gives way when hot" else ""),
                    style = MaterialTheme.typography.bodySmall, color = Palette.textDim,
                )
            }
            Switch(checked = a.enabled, onCheckedChange = { on ->
                Graph.changeAndReplan(ctx) { d -> d.copy(anchors = d.anchors.map { if (it.id == a.id) it.copy(enabled = on) else it }) }
            })
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AnchorEditor(initial: Anchor, data: AppData, isNew: Boolean, onDismiss: () -> Unit, onSave: (Anchor) -> Unit, onDelete: () -> Unit) {
    var a by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf<String?>(null) }
    val names = listOf("", "M", "T", "W", "T", "F", "S", "S")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New weekly block" else "Edit weekly block") },
        confirmButton = { TextButton(onClick = { onSave(a.copy(title = a.title.trim())) }, enabled = a.title.isNotBlank() && a.endMin > a.startMin && a.days.isNotEmpty()) { Text("Save") } },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = Palette.red) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(a.title, { a = a.copy(title = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { picking = "start" }) { Text("From ${hm(a.startMin)}") }
                    TextButton(onClick = { picking = "end" }) { Text("To ${hm(a.endMin)}") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (dow in 1..7) androidx.compose.material3.FilterChip(dow in a.days, { a = a.copy(days = if (dow in a.days) a.days - dow else a.days + dow) }, { Text(names[dow]) })
                }
                SectionLabel("How firm")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.maven.notafraud.core.model.AnchorMode.entries.forEach { m ->
                        androidx.compose.material3.FilterChip(a.mode == m, { a = a.copy(mode = m) }, { Text(m.name.lowercase()) })
                    }
                }
                if (a.mode != com.maven.notafraud.core.model.AnchorMode.HARD) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Gives way when a deadline is hot", Modifier.weight(1f))
                    Switch(a.yieldsToHot, { a = a.copy(yieldsToHot = it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Protected (no tasks, no nudges)", Modifier.weight(1f))
                    Switch(a.protected, { a = a.copy(protected = it) })
                }
                if (a.mode == com.maven.notafraud.core.model.AnchorMode.CONTAINER) {
                    SectionLabel("Fill with work from")
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        data.areas.filterNot { it.archived }.forEach { ar ->
                            androidx.compose.material3.FilterChip(ar.id in a.fillAreaIds, {
                                a = a.copy(fillAreaIds = if (ar.id in a.fillAreaIds) a.fillAreaIds - ar.id else a.fillAreaIds + ar.id)
                            }, { Text(ar.name) })
                        }
                    }
                }
            }
        },
    )
    picking?.let { which ->
        TimeDialog(if (which == "start") a.startMin else a.endMin, onDismiss = { picking = null }) { m ->
            a = if (which == "start") a.copy(startMin = m) else a.copy(endMin = m)
            picking = null
        }
    }
}

@Composable
fun ListDialog(title: String, hint: String, initial: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(initial.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.lines().filter { it.isNotBlank() }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Choose which calendars to read and which one the app writes its plan into. */
@Composable
private fun CalendarPanel(data: AppData, resumes: Long) {
    val ctx = LocalContext.current
    val cals = remember(resumes, data.settings.outputCalendarId) { com.maven.notafraud.sense.PhoneCalendar.calendars(ctx) }
    Panel {
        if (cals.isEmpty()) {
            Text("Grant Calendar (row 6) to see your calendars.", color = Palette.textDim)
            return@Panel
        }
        val out = cals.firstOrNull { it.id == data.settings.outputCalendarId }
        Text("Writes plan to: " + (out?.name ?: "nothing yet"), style = MaterialTheme.typography.titleSmall, color = if (out == null) Palette.amber else Palette.green)
        if (out == null) Text(
            "On a computer, open calendar.google.com → Other calendars → + → Create new calendar, name it \"Not a Fraud\". Give the phone a minute to sync, then pick it below.",
            color = Palette.textDim, style = MaterialTheme.typography.bodySmall,
        )
        SectionLabel("Tap to write plan here")
        cals.filter { it.writable }.forEach { c ->
            Row(Modifier.fillMaxWidth().clickable {
                Graph.update { it.copy(settings = it.settings.copy(outputCalendarId = if (it.settings.outputCalendarId == c.id) null else c.id, excludedCalendarIds = it.settings.excludedCalendarIds - c.id)) }
                Graph.replan(); Graph.refresh(ctx)
            }, verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.RadioButton(selected = c.id == data.settings.outputCalendarId, onClick = null)
                Text(c.name + (c.account?.let { " · $it" } ?: ""), maxLines = 1)
            }
        }
        SectionLabel("Each calendar is…")
        Text("Events: fixed time the plan works around. Deadlines (Canvas): each entry becomes a task due then. Tests: fixed time plus a study task leading up to it. Ignore: skipped.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
        cals.filter { it.id != data.settings.outputCalendarId }.forEach { c ->
            val mode = com.maven.notafraud.sense.PhoneCalendar.modeFor(c, data.settings)
            Text(c.name, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in com.maven.notafraud.core.model.CalMode.entries) {
                    androidx.compose.material3.FilterChip(mode == m, {
                        Graph.update { it.copy(settings = it.settings.copy(calendarModes = it.settings.calendarModes + (c.id to m), excludedCalendarIds = it.settings.excludedCalendarIds - c.id)) }
                    }, { Text(m.name.lowercase()) })
                }
            }
        }
        SectionLabel("Studying per test (Tests calendars)")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (h in listOf(2, 4, 6, 8, 10)) androidx.compose.material3.FilterChip(data.settings.studyMinPerTest == h * 60, {
                Graph.changeAndReplan(ctx) { it.copy(settings = it.settings.copy(studyMinPerTest = h * 60)) }
            }, { Text("${h}h") })
        }
        Text("Start studying up to", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (dd in listOf(5, 7, 10, 14)) androidx.compose.material3.FilterChip(data.settings.studyLeadDays == dd, {
                Graph.changeAndReplan(ctx) { it.copy(settings = it.settings.copy(studyLeadDays = dd)) }
            }, { Text("$dd days before") })
        }
        Text("Changes apply to new tests; existing study tasks can be edited like any task.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
        SwitchRow("Also spot exams in Events calendars by title", data.settings.autoExamTasks) { on -> Graph.update { it.copy(settings = it.settings.copy(autoExamTasks = on)) } }
    }
}

@Composable
private fun ProjectsPanel(data: AppData) {
    var name by remember { mutableStateOf("") }
    Panel {
        data.projects.filterNot { it.archived }.forEach { pr ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pr.name, Modifier.weight(1f))
                Text(pr.kind.name.lowercase(), style = MaterialTheme.typography.labelSmall, color = Palette.textDim)
                TextButton(onClick = { Graph.update { d -> d.copy(projects = d.projects.map { if (it.id == pr.id) it.copy(archived = true) else it }) } }) {
                    Text("Archive", color = Palette.textDim)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("New project") })
            TextButton(onClick = {
                if (name.isNotBlank()) { Graph.update { it.copy(projects = it.projects + Project(name = name.trim(), kind = ProjectKind.PROJECT)) }; name = "" }
            }) { Text("Add") }
        }
    }
}

@Composable
private fun AppPicker(title: String, selected: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { AppLabels.launchable(ctx) }
    var chosen by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    // Selected apps that aren't installed still show, so they can be unticked.
    val missing = selected.filter { s -> apps.none { it.pkg == s } }.map { AppLabels.Installed(it, AppLabels.label(ctx, it) + " (not installed)") }
    val list = (missing + apps).filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
        .sortedWith(compareByDescending<AppLabels.Installed> { it.pkg in selected }.thenBy { it.label.lowercase() })
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = { onSave(chosen) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.pkg }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = if (app.pkg in chosen) chosen - app.pkg else chosen + app.pkg },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = app.pkg in chosen, onCheckedChange = null)
                            Text(app.label)
                        }
                    }
                }
            }
        },
    )
}

private fun open(ctx: Context, intent: Intent) {
    runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { openAppInfo(ctx) }
}

private fun openAppInfo(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun readText(ctx: Context, uri: Uri): String? =
    runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()

private fun shareBackup(ctx: Context) {
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val f = File(dir, "not-a-fraud-backup-${Graph.today()}.json")
    f.writeText(Graph.store.exportJson())
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
    val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Save backup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}


/** Per-app blocking rules, so different setups can be tried (STATE.md section 11). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ProfileEditor(title: String, initial: com.maven.notafraud.core.model.BlockProfile, onDismiss: () -> Unit, onSave: (com.maven.notafraud.core.model.BlockProfile) -> Unit) {
    var p by remember { mutableStateOf(initial) }
    @Composable
    fun Chips(label: String, values: List<Int>, current: Int, fmt: (Int) -> String, set: (Int) -> Unit) {
        SectionLabel(label)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in values) androidx.compose.material3.FilterChip(current == v, { set(v) }, { Text(fmt(v)) })
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = { onSave(p) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                Chips("Reply window when you tap its notification", listOf(0, 2, 3, 5, 10), p.replyWindowMin, { if (it == 0) "off" else "${it}m" }) { p = p.copy(replyWindowMin = it) }
                SwitchRow("Typing keeps the window open", p.extendWhileTyping) { p = p.copy(extendWhileTyping = it) }
                SwitchRow("Quick-open tile", p.quickOpen) { p = p.copy(quickOpen = it) }
                if (p.quickOpen) {
                    Chips("Quick-open length", listOf(2, 3, 5, 10), p.quickOpenMin, { "${it}m" }) { p = p.copy(quickOpenMin = it) }
                    Chips("Quick-opens per day", listOf(3, 6, 10, 20), p.quickOpenPerDay, { "$it" }) { p = p.copy(quickOpenPerDay = it) }
                }
                Chips("Wait before credits unlock it", listOf(0, 15, 30, 60, 90), p.creditWaitSec, { "${it}s" }) { p = p.copy(creditWaitSec = it) }
                Chips("Instant emergencies per day", listOf(1, 2, 3, 5), p.emergencyPerDay, { "$it" }) { p = p.copy(emergencyPerDay = it) }
                Text("Calls (phone, Snapchat, FaceTime-style) are never blocked.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}


@Composable
private fun GuardLogPanel() {
    var open by remember { mutableStateOf(false) }
    Panel(Modifier.clickable { open = !open }) {
        Text(if (open) "Recent blocking decisions (newest first)" else "Tap to see the last blocking decisions", style = MaterialTheme.typography.titleSmall)
        if (open) {
            val c = LocalContext.current
            val entries = remember { com.maven.notafraud.guard.GuardLog.init(c); com.maven.notafraud.guard.GuardLog.recent().take(60) }
            if (entries.isEmpty()) Text("Nothing yet. Open a blocked app and come back.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
            for (e in entries) Row(Modifier.fillMaxWidth()) {
                Text(com.maven.notafraud.guard.GuardLog.time(e.at), style = Mono.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize), color = Palette.textDim, modifier = Modifier.padding(end = 8.dp))
                Text("${e.subject}: ${e.decision}", style = MaterialTheme.typography.bodySmall,
                    color = if (e.decision.startsWith("allowed")) Palette.amber else Palette.text)
            }
        }
    }
}
