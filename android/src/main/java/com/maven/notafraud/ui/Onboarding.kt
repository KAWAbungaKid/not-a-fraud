@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.maven.notafraud.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.Module
import com.maven.notafraud.core.seed.Templates
import com.maven.notafraud.sense.Perms

/**
 * First run (STATE.md § 25): name → goals → modules → your day → starter routine → permissions (each with its own
 * explanation; the accessibility one is Google Play's "prominent disclosure") → done. Nothing about Maven.
 */
@Composable
fun OnboardingScreen(resumes: Long) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    var a by remember { mutableStateOf(Templates.Answers(modules = emptySet())) }
    var modulesTouched by remember { mutableStateOf(false) }
    var startersTouched by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<String?>(null) }
    val steps = listOf("welcome", "goals", "modules", "day", "routine", "permissions", "done")
        .filter { it != "routine" || Module.ROUTINE in a.modules }
    val id = steps[step.coerceIn(0, steps.lastIndex)]

    fun next() {
        if (id == "goals") {
            if (!modulesTouched) a = a.copy(modules = Templates.suggestedModules(a.goals.keys))
            if (!startersTouched) a = a.copy(starters = Templates.suggestedStarters(a.goals.keys))
        }
        step = (step + 1).coerceAtMost(steps.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(Palette.bg).safeDrawingPadding().padding(20.dp)) {
        LinearProgressIndicator(progress = { (step + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (id) {
                "welcome" -> {
                    Text("Not a Fraud", style = MaterialTheme.typography.headlineMedium)
                    Text("Plans your day around what matters to you, keeps your habits honest, and makes your phone less of a trap. " +
                        "Everything stays on this phone.", color = Palette.textDim)
                    Text("Setup takes about 3 minutes. You can change all of it later.", color = Palette.textDim)
                    OutlinedTextField(a.name, { a = a.copy(name = it) }, label = { Text("What should it call you? (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                "goals" -> {
                    Text("What are you working toward?", style = MaterialTheme.typography.headlineSmall)
                    Text("Pick any. The planner gives more time to what matters more.", color = Palette.textDim)
                    for (g in Templates.GOALS) {
                        val on = g.id in a.goals
                        Panel(Modifier.clickable { a = a.copy(goals = if (on) a.goals - g.id else a.goals + (g.id to 3)) }, highlight = on) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(on, null)
                                Text(g.goal, Modifier.weight(1f))
                            }
                            if (on) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("How much it matters", color = Palette.textDim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                for (v in 1..5) FilterChip(a.goals[g.id] == v, { a = a.copy(goals = a.goals + (g.id to v)) }, { Text("$v") })
                            }
                        }
                    }
                    for (c in a.customGoals) Text("• $c")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(custom, { custom = it }, label = { Text("Your own goal") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { if (custom.isNotBlank()) { a = a.copy(customGoals = a.customGoals + custom.trim()); custom = "" } }) { Text("Add") }
                    }
                }
                "modules" -> {
                    Text("What do you want to use?", style = MaterialTheme.typography.headlineSmall)
                    Text("Suggested from your goals. Anything off is hidden and silent; turn it on any time in More → Modules.", color = Palette.textDim)
                    for (m in Module.entries) Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.label, style = MaterialTheme.typography.titleMedium)
                                Text(m.blurb, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(m in a.modules, { on -> modulesTouched = true; a = a.copy(modules = if (on) a.modules + m else a.modules - m) })
                        }
                    }
                }
                "day" -> {
                    Text("Your day", style = MaterialTheme.typography.headlineSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picking = "wake" }) { Text("Wake up ${hm(a.wakeMin)}") }
                        OutlinedButton(onClick = { picking = "bed" }) { Text("Bedtime ${hm(a.bedMin)}") }
                    }
                    Text("Do you have school or work on a schedule?", color = Palette.textDim)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (s in Templates.Schedule.entries) FilterChip(a.schedule == s, { a = a.copy(schedule = s) }, { Text(s.label) })
                    }
                    if (a.schedule == Templates.Schedule.WORK || a.schedule == Templates.Schedule.BOTH) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { picking = "workStart" }) { Text("Work from ${hm(a.workStartMin)}") }
                            OutlinedButton(onClick = { picking = "workEnd" }) { Text("to ${hm(a.workEndMin)}") }
                        }
                        val names = listOf("", "M", "T", "W", "T", "F", "S", "S")
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (d in 1..7) FilterChip(d in a.workDays, { a = a.copy(workDays = if (d in a.workDays) a.workDays - d else a.workDays + d) }, { Text(names[d]) })
                        }
                    }
                    if (a.schedule == Templates.Schedule.SCHOOL || a.schedule == Templates.Schedule.BOTH)
                        Text("Classes: after setup, add them in More → Classes, or connect your calendar and they'll come in from there.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                }
                "routine" -> {
                    Text("Starter routine", style = MaterialTheme.typography.headlineSmall)
                    Text("Times come from your wake-up and bedtime. Each one nudges you when it's due and gets louder if you're late. Edit, add or remove any later.", color = Palette.textDim)
                    for (s in Templates.STARTERS) {
                        val on = s.id in a.starters
                        val due = ((if (s.fromWake) a.wakeMin + s.offset + s.windowMin else a.bedMin + s.offset) % 1440 + 1440) % 1440
                        Row(Modifier.fillMaxWidth().clickable { startersTouched = true; a = a.copy(starters = if (on) a.starters - s.id else a.starters + s.id) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(on, null)
                            Text(s.title, Modifier.weight(1f))
                            Text("by ${hm(due)}", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if ("workout" in a.starters) {
                        Text("Workouts a week", color = Palette.textDim)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (n in 2..6) FilterChip(a.workoutsPerWeek == n, { a = a.copy(workoutsPerWeek = n) }, { Text("$n") })
                        }
                    }
                }
                "permissions" -> PermissionsStep(a.modules, resumes)
                "done" -> {
                    Text(if (a.name.isBlank()) "You're set" else "You're set, ${a.name.trim()}", style = MaterialTheme.typography.headlineSmall)
                    Text("Start on Now: it always says what to do next. Each page shows a short tip the first time you open it.", color = Palette.textDim)
                    if (Module.BLOCKING in a.modules) Text("Blocking starts with the usual time sinks (Instagram, TikTok, YouTube, Reddit…). Change the list in More → Blocking & credits.", color = Palette.textDim)
                    Text("Want workouts or food tracked too? Not a Troglodyte and Not a Twig connect to this app when installed.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            if (id == "done") Button(onClick = {
                Graph.update { Templates.apply(it, a, Graph.now()) }
                Graph.replan(); Graph.refresh(ctx)
            }) { Text("Start") }
            else Button(onClick = { next() }, enabled = id != "goals" || a.goals.isNotEmpty() || a.customGoals.isNotEmpty()) { Text(if (id == "welcome") "Get started" else "Next") }
        }
    }

    picking?.let { which ->
        val cur = when (which) { "wake" -> a.wakeMin; "bed" -> a.bedMin; "workStart" -> a.workStartMin; else -> a.workEndMin }
        TimeDialog(cur, onDismiss = { picking = null }) { m ->
            a = when (which) { "wake" -> a.copy(wakeMin = m); "bed" -> a.copy(bedMin = m); "workStart" -> a.copy(workStartMin = m); else -> a.copy(workEndMin = m) }
            picking = null
        }
    }
}

/** One permission at a time, each saying what it's for. Skippable; Setup checks lists anything missing later. */
@Composable
private fun PermissionsStep(modules: Set<Module>, resumes: Long) {
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val r = resumes
    var consent by remember { mutableStateOf(false) }
    fun open(action: String, pkg: Boolean = false) = runCatching {
        ctx.startActivity(Intent(action).apply { if (pkg) data = Uri.parse("package:${ctx.packageName}") }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Text("Permissions", style = MaterialTheme.typography.headlineSmall)
    Text("Only what your modules need. Each can be skipped and granted later in More → Setup checks.", color = Palette.textDim)
    PermRow("Notifications", "Nudges when a routine item is due, when it's time to start something, and when blocking time runs out.", Perms.notifications(ctx)) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    if (Module.BLOCKING in modules || Module.STATS in modules)
        PermRow("Usage access", "Counts how long you spend in each app (for credits, stats and the brick ladder). It never sees what you do inside apps.", Perms.usageAccess(ctx)) {
            open(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }
    if (Module.BLOCKING in modules) Panel(highlight = !Perms.accessibility(ctx)) {
        Text("Blocking uses Accessibility", style = MaterialTheme.typography.titleSmall)
        Text(
            "To block apps and websites, Not a Fraud uses Android's Accessibility service. With it on, the app sees which app is open; " +
                "in web browsers, the address in the address bar; and on Android's own Settings screens, the screen's labels (to stop Not a Fraud " +
                "being switched off in a weak moment). It uses this only to decide whether to block. It never reads messages, passwords or what's on " +
                "other screens, and nothing leaves your phone.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (Perms.accessibility(ctx)) Text("On", color = Palette.green)
        else {
            Row(Modifier.clickable { consent = !consent }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(consent, null)
                Text("I understand and agree", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(enabled = consent, onClick = { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("Turn it on: Installed apps → Not a Fraud") }
            Text("Installed from a file (not the Play Store)? Android may grey the switch out: App info → ⋮ → Allow restricted settings, then try again.",
                color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (Module.PLANNER in modules)
        PermRow("Calendar (optional)", "Reads your calendar so classes, work and events are planned around. Nothing is changed unless you choose to write the plan back.", Perms.calendar(ctx)) {
            open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg = true)
        }
    PermRow("Run in the background", "So nudges and blocking keep working when the app is closed (Samsung especially puts apps to sleep).", Perms.batteryUnrestricted(ctx)) {
        open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}

@Composable
private fun PermRow(title: String, why: String, granted: Boolean, onGrant: () -> Unit) {
    Panel(highlight = !granted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(why, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
            }
            if (granted) Text("On", color = Palette.green) else TextButton(onClick = onGrant) { Text("Allow") }
        }
    }
}

/** More → Modules: switch parts on and off. Turning Blocking or Routine off waits for the morning (settings lock). */
@Composable
fun ModulesScreen(data: com.maven.notafraud.core.model.AppData) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel { com.maven.feedback.FeedbackSettings("Not a Fraud") }
        for (m in Module.entries) Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.label, style = MaterialTheme.typography.titleMedium)
                    Text(m.blurb, color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                    if (data.pending.any { it.kind == com.maven.notafraud.core.model.ChangeKind.MODULE_OFF && it.pkg == m.name })
                        Text("Switching off tomorrow morning, if you still want it", color = Palette.amber, style = MaterialTheme.typography.bodySmall)
                }
                Switch(m in data.settings.modules, { on ->
                    Confirm.switch(m.label, on) { Graph.update { com.maven.notafraud.core.store.Lock.setModule(it, m, on, Graph.now()) } }
                })
            }
        }
        Text("Off means hidden and silent. Nothing is deleted; switch it back on and everything is where you left it.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
    }
}

/** First-visit tips: what each page is for, in two lines. */
val TIPS = mapOf(
    "now" to "Now always answers \"what should I do?\". Tap Start on the suggestion and the timer runs; your Today's 3 are the things that make the day a win.",
    "plan" to "Your day, planned around fixed times. Tap a block to start, move or skip it. Replan rebuilds the rest of the day.",
    "routine" to "Daily habits. Tick them off; late ones nudge you and then get angry. The number on the right is how many days in a row.",
    "tasks" to "Everything you need to do, grouped under goals and projects. Give tasks a due date and a time estimate and the planner fits them in.",
    "stats" to "Scores from what you actually do (sleep, workouts, focus). A low stat gives its areas more time in the plan.",
    "skills" to "Each skill is a star. Practice time lights it up; draw your own constellation in a tree's settings.",
    "blocking" to "Blocked apps open after a short wait if you have credits; focus time earns credits. Emergencies and calls always work.",
    "ladder" to "The phone gets stricter as the day drifts and comes back down when you start something. Pick your Level 3 apps first.",
)

@Composable
fun PageTip(page: String, data: com.maven.notafraud.core.model.AppData) {
    val text = TIPS[page] ?: return
    if (page in data.settings.tipsSeen) return
    Panel(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), highlight = true) {
        Text(text, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { Graph.update { it.copy(settings = it.settings.copy(tipsSeen = it.settings.tipsSeen + page)) } }) { Text("Got it") }
    }
}

/** Which module a page belongs to (null = always shown). */
fun moduleOf(page: String): Module? = when (page) {
    "plan", "classes", "blocks" -> Module.PLANNER
    "routine" -> Module.ROUTINE
    "stats" -> Module.STATS
    "skills" -> Module.SKILLS
    "blocking", "ladder" -> Module.BLOCKING
    else -> null
}
