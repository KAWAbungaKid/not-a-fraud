package com.maven.notafraud.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewTimeline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.maven.notafraud.Graph
import com.maven.notafraud.sense.Perms
import com.maven.notafraud.work.Workers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var tab by mutableStateOf(TAB_NOW)
    /** Bumped on every resume so screens re-read permission state. */
    private var resumes by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Graph.init(this)
        intent?.getStringExtra(EXTRA_TAB)?.let { tab = it }
        setContent {
            val d by Graph.store.state.collectAsState()
            NafTheme(d.settings.accent) { App() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { tab = it }
    }

    override fun onResume() {
        super.onResume()
        resumes++
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { Workers.syncSensors(this@MainActivity) } }
    }

    @Composable
    private fun App() {
        val ctx = LocalContext.current
        val data by Graph.store.state.collectAsState()
        val now by produceState(Graph.now()) {
            while (true) { value = Graph.now(); delay(20_000) }
        }
        val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Graph.refresh(ctx) }
        LaunchedEffect(Unit) {
            if (!Perms.notifications(ctx)) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    tabItem(TAB_NOW, "Now", Icons.Filled.PlayArrow)
                    tabItem(TAB_PLAN, "Plan", Icons.Filled.ViewTimeline)
                    val angry = remember(data, now / 60_000) {
                        com.maven.notafraud.core.routine.RoutinePolicy.statuses(data, now).count { it.mood == com.maven.notafraud.core.routine.Mood.ANGRY }
                    }
                    NavigationBarItem(
                        selected = tab == TAB_ROUTINE,
                        onClick = { tab = TAB_ROUTINE },
                        icon = { BadgedBox(badge = { if (angry > 0) Badge { Text("$angry") } }) { Icon(Icons.Filled.Person, null) } },
                        label = { Text("You") },
                    )
                    tabItem(TAB_TASKS, "Tasks", Icons.Filled.AccountTree)
                    NavigationBarItem(
                        selected = tab == TAB_SETUP,
                        onClick = { tab = TAB_SETUP },
                        icon = { Icon(Icons.Filled.Settings, null) },
                        label = { Text("More") },
                    )
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    TAB_NOW -> NowScreen(data, now, onOpenTab = { tab = it })
                    TAB_PLAN -> PlanScreen(data, now)
                    TAB_ROUTINE -> YouScreen(data, now)
                    TAB_TASKS -> TasksScreen(data, now)
                    TAB_SETUP -> SetupScreen(data, resumes)
                }
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.tabItem(id: String, label: String, icon: ImageVector) {
        NavigationBarItem(
            selected = tab == id,
            onClick = { tab = id },
            icon = { Icon(icon, null) },
            label = { Text(label) },
        )
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_NOW = "now"
        const val TAB_PLAN = "plan"
        const val TAB_TASKS = "tasks"
        const val TAB_ROUTINE = "routine"
        const val TAB_SETUP = "setup"
    }
}
