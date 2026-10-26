package com.maven.workout.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.maven.workout.Graph
import com.maven.workout.notify.Reminder

/** Pages opened on top of the tabs. */
sealed class Route(val title: String) {
    data object Workout : Route("Workout")
    data class ExerciseEdit(val id: String) : Route("Exercise")
    data class ProgressionEdit(val id: String) : Route("Type")
    data class ProgramEdit(val id: String) : Route("Program")
    data class DayEdit(val programId: String, val dayId: String) : Route("Day")
    data class SessionDetail(val id: String) : Route("Workout")
}

class MainActivity : ComponentActivity() {
    private var tab by mutableStateOf("today")
    private val stack = mutableStateListOf<Route>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Graph.init(this)
        if (Graph.data.active != null) stack.add(Route.Workout)
        setContent { WorkoutTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        Graph.roll()
        Reminder.schedule(this)
    }

    private fun open(r: Route) { stack.add(r) }
    private fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    @Composable
    private fun App() {
        val ctx = LocalContext.current
        val d by Graph.store.state.collectAsState()
        val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        BackHandler(enabled = stack.isNotEmpty() || tab != "today") { if (stack.isNotEmpty()) back() else tab = "today" }

        val top = stack.lastOrNull()
        Scaffold(
            bottomBar = {
                if (top == null) NavigationBar {
                    for ((id, label, icon) in TABS) NavigationBarItem(
                        selected = tab == id, onClick = { tab = id },
                        icon = { Icon(icon, null) }, label = { Text(label, maxLines = 1) },
                    )
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                Column {
                    if (top != null && top !is Route.Workout) Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Text(top.title, style = MaterialTheme.typography.titleLarge)
                    }
                    Box(Modifier.weight(1f)) {
                        when (top) {
                            null -> when (tab) {
                                "today" -> TodayScreen(d, onOpen = ::open)
                                "history" -> HistoryScreen(d, onOpen = ::open)
                                "exercises" -> ExercisesScreen(d, onOpen = ::open)
                                "program" -> ProgramScreen(d, onOpen = ::open)
                                else -> SettingsScreen(d)
                            }
                            Route.Workout -> WorkoutScreen(d, onClose = { back() }, onOpen = ::open)
                            is Route.ExerciseEdit -> ExerciseEditScreen(d, top.id, onBack = { back() })
                            is Route.ProgressionEdit -> ProgressionEditScreen(d, top.id, onBack = { back() })
                            is Route.ProgramEdit -> ProgramEditScreen(d, top.id, onOpen = ::open, onBack = { back() })
                            is Route.DayEdit -> DayEditScreen(d, top.programId, top.dayId, onBack = { back() })
                            is Route.SessionDetail -> SessionDetailScreen(d, top.id, onBack = { back() })
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val TABS = listOf(
            Triple("today", "Today", Icons.Filled.Today),
            Triple("history", "History", Icons.Filled.History),
            Triple("exercises", "Exercises", Icons.Filled.FitnessCenter),
            Triple("program", "Program", Icons.Filled.CalendarMonth),
            Triple("settings", "Settings", Icons.Filled.Settings),
        )
    }
}
