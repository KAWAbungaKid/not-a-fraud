package com.maven.notafraud.guard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.maven.notafraud.Graph
import com.maven.notafraud.core.guard.Credits
import com.maven.notafraud.core.store.Actions
import com.maven.notafraud.sense.AppLabels
import com.maven.notafraud.ui.MainActivity
import com.maven.notafraud.ui.NafTheme
import com.maven.notafraud.ui.Palette
import com.maven.notafraud.ui.Panel
import com.maven.notafraud.ui.SectionLabel
import kotlinx.coroutines.delay

/**
 * Shown over a blocked app. Three ways forward: start a task, spend earned credits, or an
 * emergency unlock with friction. Never a dead end — a lock you resent gets uninstalled.
 */
class BlockActivity : ComponentActivity() {

    private var pkg by mutableStateOf("")
    private var returnTo: String? = null
    val subject: String get() = pkg

    /** Called when a reply window opens while this screen is up. */
    fun openNow() = runOnUiThread { openTarget() }

    override fun onResume() { super.onResume(); current = this }
    override fun onPause() { if (current === this) current = null; super.onPause() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Graph.init(this)
        pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        returnTo = intent.getStringExtra(EXTRA_RETURN_TO)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
        setContent { NafTheme { BlockScreen(pkg) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        returnTo = intent.getStringExtra(EXTRA_RETURN_TO)
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun openTarget() {
        val target = if (pkg.startsWith(com.maven.notafraud.core.guard.Sites.PREFIX)) returnTo else pkg
        target?.let(packageManager::getLaunchIntentForPackage)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
        GuardService.instance?.reevaluate()
    }

    private fun startTask(taskId: String?) {
        val now = Graph.now()
        Graph.update { Actions.startSession(it, taskId, null, now) }
        Graph.refresh(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    @Composable
    private fun BlockScreen(pkg: String) {
        val data by Graph.store.state.collectAsState()
        val label = remember(pkg) { AppLabels.label(this, pkg) }
        val now = remember(data) { Graph.nowState() }
        val profile = data.rules.profile(pkg)
        val balance = data.ledger.balanceMin
        val stillTexting = remember(data) { Credits.canStillTexting(data.ledger, pkg, Graph.now()) }
        var paying by remember { mutableIntStateOf(0) }

        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.bg)
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Text("$label is locked", style = MaterialTheme.typography.headlineSmall)
            Text(data.settings.creed, color = Palette.textDim)

            if (stillTexting) {
                Panel(highlight = true) {
                    SectionLabel("Your window ended")
                    Button(onClick = {
                        val n = Graph.now()
                        Graph.update { d -> d.copy(ledger = Credits.freeWindow(d.ledger, pkg, 3, n, "Still texting: $label")) }
                        openTarget()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Still texting: 3 more minutes") }
                    Text("Typing keeps the window open on its own. This is for when you were reading or about to reply.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (profile.askPurpose) PurposePanel(pkg, label, profile)

            Panel(highlight = !stillTexting && !profile.askPurpose) {
                SectionLabel("Right now")
                Text(now.headline, style = MaterialTheme.typography.titleLarge)
                if (now.detail.isNotBlank()) Text(now.detail, color = Palette.textDim)
                val task = now.suggested
                Button(onClick = { startTask(task?.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (task != null) "Start: ${task.title}" else "Start a focus session")
                }
                OutlinedButton(onClick = { startTaskWalk() }, modifier = Modifier.fillMaxWidth()) { Text("Walk 5 minutes instead") }
            }

            val paused = com.maven.notafraud.core.workout.WorkoutLink.creditsPaused(data, Graph.now())
            if (paused) Panel(highlight = true) {
                SectionLabel("Credits are paused")
                Text("Today's workout is what keeps this week's minimum. " + (data.workout?.message ?: ""), color = Palette.textDim)
                Button(onClick = { com.maven.notafraud.sense.WorkoutSync.open(this@BlockActivity); finish() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Start workout" + (data.workout?.suggestedToday?.let { ": $it" } ?: ""))
                }
                Text("Starting it (Short mode is fine) turns credits back on. Emergencies still work.", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
            } else Panel {
                SectionLabel("Credits")
                if (paying > 0) {
                    PayCountdown(profile.creditWaitSec, onCancel = { paying = 0 }) {
                        val n = Graph.now()
                        Graph.update { d -> Credits.spend(d.ledger, pkg, paying, label, n)?.let { d.copy(ledger = it) } ?: d }
                        openTarget()
                    }
                } else {
                    Text("${balance.toInt()} min banked", style = MaterialTheme.typography.displaySmall, color = Palette.green)
                    Text("Earn 1:1 by reading in focus apps or logging task time. Spending waits ${profile.creditWaitSec}s.", color = Palette.textDim)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (m in listOf(5, 10, 15, 30)) {
                            FilledTonalButton(onClick = { paying = m }, enabled = Credits.canSpend(data.ledger, m)) { Text("${m}m") }
                        }
                    }
                }
            }

            val usedToday = Credits.emergencyUsesToday(data.ledger, Graph.startOfToday())
            val left = profile.emergencyPerDay - usedToday
            val extra = Credits.extraEmergencyLeft(data.ledger, profile, Graph.startOfToday())
            if (left > 0) OutlinedButton(
                onClick = {
                    val n = Graph.now()
                    Graph.update { d ->
                        Credits.emergencyInstant(d.ledger, d.settings, profile, pkg, label, n, Graph.startOfToday())?.let { d.copy(ledger = it) } ?: d
                    }
                    openTarget()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Emergency: open now (${data.settings.emergencyUnlockMin} min · $left left today)") }
            else if (extra > 0) Panel {
                SectionLabel("Out of emergencies · $extra more if you type it")
                var typed by remember { mutableStateOf("") }
                Text("Type: \"${data.settings.emergencyPhrase}\"", color = Palette.textDim)
                OutlinedTextField(typed, { typed = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Type it out") })
                val ok = Credits.phraseMatches(typed, data.settings.emergencyPhrase)
                OutlinedButton(onClick = {
                    val n = Graph.now()
                    Graph.update { d -> Credits.emergencyWithPhrase(d.ledger, d.settings, profile, pkg, label, typed, n, Graph.startOfToday())?.let { d.copy(ledger = it) } ?: d }
                    GuardLog.add(label, "extra emergency (typed)")
                    openTarget()
                }, enabled = ok, modifier = Modifier.fillMaxWidth()) { Text("Open for ${data.settings.emergencyUnlockMin} min") }
            } else Text("No emergency unlocks left today.", color = Palette.textDim)
            TextButton(onClick = { goHome() }) { Text("Go home") }
        }
    }

    /** "What are you doing?" Open it by saying what for; it asks again every few minutes, and today's answers stay on screen. */
    @Composable
    private fun PurposePanel(pkg: String, label: String, profile: com.maven.notafraud.core.model.BlockProfile) {
        val data by Graph.store.state.collectAsState()
        val start = Graph.startOfToday()
        val today = data.ledger.purposeLog.filter { it.pkg == pkg && it.at >= start }
        val used = Credits.purposeUsedMin(data.ledger, profile, pkg, start)
        val budgetLeft = profile.purposeDailyMin - used
        var what by remember { mutableStateOf("") }
        Panel(highlight = true) {
            SectionLabel(if (today.isEmpty()) "What are you opening $label for?" else "Still on $label. What are you doing now?")
            OutlinedTextField(what, { what = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text(if (today.isEmpty()) "e.g. how to replace a bike chain" else "Be honest: \"scrolling\" counts") })
            Button(
                onClick = {
                    val n = Graph.now()
                    Graph.update { d -> Credits.purpose(d.ledger, profile, pkg, label, what, n, start)?.let { d.copy(ledger = it) } ?: d }
                    GuardLog.add(label, "purpose: ${what.trim()}")
                    openTarget()
                },
                enabled = what.trim().length >= 3 && budgetLeft >= profile.purposeCheckMin,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (budgetLeft >= profile.purposeCheckMin) "Open for ${profile.purposeCheckMin} min" else "Today's ${profile.purposeDailyMin} min are used") }
            Text("Asks again every ${profile.purposeCheckMin} min · ${budgetLeft.coerceAtLeast(0)} of ${profile.purposeDailyMin} min left today", color = Palette.textDim, style = MaterialTheme.typography.bodySmall)
            if (today.isNotEmpty()) {
                SectionLabel("You said today")
                val fmt = java.text.SimpleDateFormat("h:mm", java.util.Locale.US)
                for (e in today.takeLast(8).reversed()) Text("${fmt.format(java.util.Date(e.at))}  ${e.what}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    private fun startTaskWalk() {
        val now = Graph.now()
        Graph.update { Actions.startSession(it, null, "Walk", now) }
        Graph.refresh(this)
        goHome()
    }

    /** The wait before banked credits unlock: staring at a countdown kills the urge to scroll. */
    @Composable
    private fun PayCountdown(seconds: Int, onCancel: () -> Unit, onDone: () -> Unit) {
        var left by remember { mutableIntStateOf(seconds) }
        LaunchedEffect(Unit) {
            while (left > 0) { delay(1000); left-- }
            onDone()
        }
        Text("Opening in $left s", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text("Still want it? Or start something instead.", color = Palette.textDim)
        TextButton(onClick = onCancel) { Text("Never mind") }
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_RETURN_TO = "returnTo"
        @Volatile var current: BlockActivity? = null
            private set
    }
}
