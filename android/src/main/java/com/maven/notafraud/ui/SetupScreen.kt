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
    val calLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
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
            PermRow("4. Message mirror (Notification access)", p.listener, "Shows Snapchat messages in Inbox so Snapchat can stay blocked. Same restricted-settings note as the guard.") {
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
            PermRow("6. Calendar", p.calendar, "Reads your phone calendar (Google Calendar syncs into it) so events block time in the plan.") {
                calLauncher.launch(Manifest.permission.READ_CALENDAR)
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
                TextButton(onClick = { picker = Picker.BLOCKED }) { Text("Blocked apps (${data.rules.blocked.size})") }
                TextButton(onClick = { picker = Picker.FOCUS }) { Text("Focus apps that earn credits (${data.rules.focus.size})") }
                TextButton(onClick = { picker = Picker.MIRRORED }) { Text("Mirrored into Inbox (${data.rules.mirrored.size})") }
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

        item { SectionLabel("Weekly anchors") }
        val recurring = data.anchors.filter { it.dates.isEmpty() }
        items(recurring, key = { it.id }) { a -> AnchorRow(a) }
        item {
            val dated = data.anchors.filter { it.dates.isNotEmpty() }
            val days = dated.flatMap { it.dates }.distinct().sorted()
            Panel {
                Text("Research session days", style = MaterialTheme.typography.titleSmall)
                Text(days.joinToString(", ") { it.substring(5) }, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
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
private fun AnchorRow(a: Anchor) {
    val ctx = LocalContext.current
    fun hm(m: Int) = "%d:%02d".format(((m / 60) + 11) % 12 + 1, m % 60) + if (m < 12 * 60) "a" else "p"
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.title)
                Text(
                    a.days.sorted().joinToString(" ") { DAY_NAMES[it] } + " · " + hm(a.startMin) + "–" + hm(a.endMin) + if (a.protected) " · protected" else "",
                    style = MaterialTheme.typography.bodySmall, color = Palette.textDim,
                )
            }
            Switch(checked = a.enabled, onCheckedChange = { on ->
                Graph.changeAndReplan(ctx) { d -> d.copy(anchors = d.anchors.map { if (it.id == a.id) it.copy(enabled = on) else it }) }
            })
        }
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
