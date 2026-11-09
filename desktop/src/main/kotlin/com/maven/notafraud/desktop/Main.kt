package com.maven.notafraud.desktop

import com.maven.notafraud.core.model.liveTasks
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.Notification
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.plan.BlockKind
import com.maven.notafraud.core.plan.NowResolver
import com.maven.notafraud.core.plan.Planning
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.score.Scorer
import com.maven.notafraud.core.seed.Seed
import com.maven.notafraud.core.store.Actions
import com.maven.notafraud.core.store.DataStore
import kotlinx.coroutines.delay
import java.io.File

private val green = Color(0xFF8CFF6E)
private val dim = Color(0xFF9AA897)
private val bg = Color(0xFF0D110D)
private val mono = TextStyle(fontFamily = FontFamily.Monospace)

private val colors = darkColorScheme(
    primary = green, onPrimary = Color(0xFF0B1A08), primaryContainer = Color(0xFF24401C),
    background = bg, surface = Color(0xFF151B15), onSurface = Color(0xFFE4EDE1), onBackground = Color(0xFFE4EDE1),
    surfaceVariant = Color(0xFF1E261E), onSurfaceVariant = dim,
)

/** Tray icon drawn in code: a ring with a check. No resource files to ship. */
private object CheckIcon : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        val w = size.minDimension
        drawCircle(green, radius = w * 0.42f, style = Stroke(width = w * 0.09f))
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.30f, w * 0.52f); lineTo(w * 0.45f, w * 0.66f); lineTo(w * 0.72f, w * 0.36f)
        }
        drawPath(p, green, style = Stroke(width = w * 0.11f, cap = StrokeCap.Round))
    }
}

fun dataDir(): File {
    val base = System.getenv("APPDATA")?.let { File(it) } ?: File(System.getProperty("user.home"), ".config")
    return File(base, "NotAFraud").apply { mkdirs() }
}

fun main(args: Array<String>) {
    val store = DataStore(File(dataDir(), "data.json")) { com.maven.notafraud.core.seed.Templates.fresh(System.currentTimeMillis(), java.time.ZoneId.systemDefault().id).let { it.copy(settings = it.settings.copy(onboarded = true)) } }
    val startMinimized = "--minimized" in args

    application {
        val trayState = rememberTrayState()
        var visible by remember { mutableStateOf(!startMinimized) }
        val tracker = remember { Tracker(store) { title, text -> trayState.sendNotification(Notification(title, text, Notification.Type.Info)) } }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { tracker.start(scope) }

        val data by store.state.collectAsState()
        val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(15_000) } }
        val state = remember(data, now / 60_000) { Planning.nowState(data, now) }

        Tray(
            icon = CheckIcon,
            state = trayState,
            tooltip = "${state.headline}${if (state.detail.isNotBlank()) " · " + state.detail else ""}".take(120),
            onAction = { visible = true },
            menu = {
                Item(state.headline.take(60), enabled = false, onClick = {})
                if (data.active != null) Item("Stop session", onClick = { store.update { Actions.stopSession(it, System.currentTimeMillis()) } })
                else state.suggested?.let { t -> Item("Start: ${t.title.take(40)}", onClick = { store.update { Actions.startSession(it, t.id, null, System.currentTimeMillis()) } }) }
                Item("Open", onClick = { visible = true })
                Separator()
                Item("Quit", onClick = { tracker.flush(); exitApplication() })
            },
        )

        if (visible) {
            Window(
                onCloseRequest = { visible = false; tracker.flush() },
                title = "Not a Fraud",
                icon = CheckIcon,
                state = rememberWindowState(size = DpSize(460.dp, 760.dp)),
            ) {
                MaterialTheme(colorScheme = colors) {
                    Box(Modifier.fillMaxSize().background(bg)) { DesktopApp(store, tracker, data, now) }
                }
            }
        }
    }
}

@Composable
private fun Panel(highlight: Boolean = false, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
    ) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() } }
}

@Composable
private fun Label(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = dim)

@Composable
private fun DesktopApp(store: DataStore, tracker: Tracker, data: AppData, now: Long) {
    var tab by remember { mutableIntStateOf(0) }
    Column {
        TabRow(selectedTabIndex = tab) {
            listOf("Now", "Plan", "PC usage", "Settings").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        Box(Modifier.padding(14.dp)) {
            when (tab) {
                0 -> NowTab(store, tracker, data, now)
                1 -> PlanTab(data, now)
                2 -> UsageTab(data)
                else -> SettingsTab(store, tracker, data)
            }
        }
    }
}

@Composable
private fun NowTab(store: DataStore, tracker: Tracker, data: AppData, now: Long) {
    val state = remember(data, now / 60_000) { Planning.nowState(data, now) }
    val live by tracker.live.collectAsState()
    val plan = remember(data, now / 60_000) { Planning.ensureToday(data, now).todayPlan!! }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel(highlight = true) {
                Label(if (state.working) "Working" else "Right now")
                Text(state.headline, style = MaterialTheme.typography.headlineSmall)
                if (state.detail.isNotBlank()) Text(state.detail, color = dim)
                val active = data.active
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (active != null) {
                        Text(Time.durationText(((now - active.start) / Time.MIN).toInt()), style = MaterialTheme.typography.titleLarge.merge(mono), color = green)
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { store.update { Actions.stopSession(it, System.currentTimeMillis()) } }) { Text("Stop") }
                    } else if (!state.protected) {
                        Button(onClick = { store.update { Actions.startSession(it, state.suggested?.id, null, System.currentTimeMillis()) } }) {
                            Text(if (state.suggested != null) "Start" else "Start session")
                        }
                    }
                    state.suggested?.takeIf { active == null }?.let { t ->
                        OutlinedButton(onClick = { store.update { Planning.replan(Actions.setDone(it, t.id, true, System.currentTimeMillis()), System.currentTimeMillis()) } }) { Text("Done") }
                    }
                }
            }
        }
        item {
            Panel {
                Label("In front")
                Text(
                    when {
                        live.idle -> "Idle (not counted)"
                        live.process.isBlank() -> if (Win32.isWindows) "…" else "Window tracking only runs on Windows."
                        else -> "${live.process} — ${live.title.take(60)}"
                    },
                    color = if (live.distraction) Color(0xFFFFC857) else MaterialTheme.colorScheme.onSurface,
                )
                if (live.distraction) Text("Distraction. Nudges escalate the longer it stays in front.", color = dim, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (plan.note.isNotBlank()) item { Text(plan.note, color = Color(0xFFFFC857)) }
        item { Label("Up next") }
        items(Scorer.rank(data.liveTasks(), now, 60, plan.energy).take(6), key = { it.first.id }) { (t, _) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, Modifier.weight(1f))
                TextButton(onClick = { store.update { Actions.startSession(it, t.id, null, System.currentTimeMillis()) } }) { Text("Start") }
            }
        }
    }
}

@Composable
private fun PlanTab(data: AppData, now: Long) {
    val zone = Time.zone(data.settings.zoneId)
    val plan = remember(data, now / 60_000) { Planning.ensureToday(data, now).todayPlan!! }
    val current = NowResolver.pickCurrent(plan.blocks, now)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(plan.blocks, key = { "${it.start}-${it.kind}-${it.title}" }) { b ->
            val past = b.end <= now
            Row(
                Modifier.fillMaxWidth()
                    .background(if (b == current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                Text("${Time.clock(b.start, zone)}–${Time.clock(b.end, zone)}", style = mono, color = dim.copy(alpha = if (past) 0.5f else 1f), modifier = Modifier.width(110.dp))
                Text(
                    b.title,
                    color = when { past -> dim.copy(alpha = 0.5f); b.kind == BlockKind.TASK -> green; else -> MaterialTheme.colorScheme.onSurface },
                )
            }
        }
    }
}

@Composable
private fun UsageTab(data: AppData) {
    val zone = Time.zone(data.settings.zoneId)
    val today = Time.dateOf(System.currentTimeMillis(), zone).toString()
    val pc = data.usage.firstOrNull { it.date == today && it.device == Tracker.DEVICE }
    val rows = pc?.minutesByApp?.entries?.sortedByDescending { it.value }.orEmpty()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { Text("Today on this PC: ${Time.durationText(rows.sumOf { it.value })}", style = MaterialTheme.typography.titleMedium) }
        item { Text("Counted only while you're active (no input for 5 min = idle). Saved every minute.", color = dim, style = MaterialTheme.typography.bodySmall) }
        items(rows, key = { it.key }) { (p, m) ->
            Row { Text(pc?.labels?.get(p) ?: p, Modifier.weight(1f)); Text(Time.durationText(m), style = mono, color = dim) }
        }
    }
}

@Composable
private fun SettingsTab(store: DataStore, tracker: Tracker, data: AppData) {
    var titles by remember { mutableStateOf(data.rules.desktopBlockedTitles.joinToString(", ")) }
    var procs by remember { mutableStateOf(data.rules.desktopBlockedProcesses.joinToString(", ")) }
    var autostart by remember { mutableStateOf(Win32.autostartEnabled()) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel {
                Label("Distractions")
                OutlinedTextField(titles, { titles = it }, label = { Text("Window titles containing (comma separated)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(procs, { procs = it }, label = { Text("Programs (e.g. steam.exe, discord.exe)") }, modifier = Modifier.fillMaxWidth())
                Button(onClick = {
                    fun split(s: String) = s.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
                    store.update { it.copy(rules = it.rules.copy(desktopBlockedTitles = split(titles), desktopBlockedProcesses = split(procs))) }
                }) { Text("Save") }
            }
        }
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Minimize distractions after the second nudge", Modifier.weight(1f))
                    Switch(data.settings.desktopMinimize, { on -> store.update { it.copy(settings = it.settings.copy(desktopMinimize = on)) } })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Start with Windows (in the tray)", Modifier.weight(1f))
                    Switch(autostart, { on -> if (Win32.setAutostart(on)) autostart = on }, enabled = Win32.isWindows)
                }
            }
        }
        item {
            Panel {
                Label("Data")
                Text("Stored in ${dataDir().absolutePath}", color = dim, style = MaterialTheme.typography.bodySmall)
                Text("Phone and PC keep separate data until sync is turned on.", color = dim, style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}
