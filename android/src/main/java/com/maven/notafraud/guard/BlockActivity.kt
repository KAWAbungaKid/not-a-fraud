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
        val balance = data.ledger.balanceMin
        var emergency by remember { mutableStateOf(false) }

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

            Panel(highlight = true) {
                SectionLabel("Right now")
                Text(now.headline, style = MaterialTheme.typography.titleLarge)
                if (now.detail.isNotBlank()) Text(now.detail, color = Palette.textDim)
                val task = now.suggested
                Button(onClick = { startTask(task?.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (task != null) "Start: ${task.title}" else "Start a focus session")
                }
                OutlinedButton(onClick = { startTaskWalk() }, modifier = Modifier.fillMaxWidth()) { Text("Walk 5 minutes instead") }
            }

            Panel {
                SectionLabel("Credits")
                Text("${balance.toInt()} min banked", style = MaterialTheme.typography.displaySmall, color = Palette.green)
                Text("Earn 1:1 by reading in focus apps or logging task time.", color = Palette.textDim)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in listOf(5, 10, 15, 30)) {
                        FilledTonalButton(
                            onClick = {
                                val n = Graph.now()
                                Graph.update { d -> Credits.spend(d.ledger, pkg, m, label, n)?.let { d.copy(ledger = it) } ?: d }
                                openTarget()
                            },
                            enabled = Credits.canSpend(data.ledger, m),
                        ) { Text("${m}m") }
                    }
                }
            }

            if (!emergency) {
                TextButton(onClick = { emergency = true }) { Text("Emergency unlock", color = Palette.textDim) }
            } else {
                EmergencyPanel(pkg, label)
            }
            TextButton(onClick = { goHome() }) { Text("Go home") }
        }
    }

    private fun startTaskWalk() {
        val now = Graph.now()
        Graph.update { Actions.startSession(it, null, "Walk", now) }
        Graph.refresh(this)
        goHome()
    }

    @Composable
    private fun EmergencyPanel(pkg: String, label: String) {
        val s = Graph.data.settings
        val usedToday = Credits.emergencyUsesToday(Graph.data.ledger, Graph.startOfToday())
        val left = s.emergencyDailyLimit - usedToday
        var seconds by remember { mutableIntStateOf(s.emergencyWaitSec) }
        var typed by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { while (seconds > 0) { delay(1000); seconds-- } }

        Panel {
            SectionLabel("Emergency unlock")
            if (left <= 0) {
                Text("No emergency unlocks left today (${s.emergencyDailyLimit} per day).", color = Palette.amber)
                return@Panel
            }
            Text("${s.emergencyUnlockMin} free minutes. $left left today. Logged.", color = Palette.textDim)
            if (seconds > 0) {
                Text("Wait $seconds s", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            } else {
                Text("Type: \"${s.emergencyPhrase}\"", color = Palette.textDim)
                OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = {
                        val n = Graph.now()
                        Graph.update { d ->
                            Credits.emergency(d.ledger, d.settings, pkg, label, n, Graph.startOfToday())?.let { d.copy(ledger = it) } ?: d
                        }
                        openTarget()
                    },
                    enabled = typed.trim().equals(s.emergencyPhrase, ignoreCase = true),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Unlock $label for ${s.emergencyUnlockMin} min") }
            }
        }
    }

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_RETURN_TO = "returnTo"
    }
}
