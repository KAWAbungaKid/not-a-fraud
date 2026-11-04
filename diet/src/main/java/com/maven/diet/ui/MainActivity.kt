package com.maven.diet.ui

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
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MonitorWeight
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.material.icons.filled.CalendarMonth
import com.maven.diet.Graph
import com.maven.diet.link.Health
import com.maven.diet.link.WorkoutLink
import com.maven.diet.notify.Nagger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

sealed class Route(val title: String) {
    data class AddFood(val date: String, val slot: String) : Route("Add to $slot")
    data class FoodEdit(val id: String?) : Route("Food")
    data class MealEdit(val id: String) : Route("Meal / recipe")
}

class MainActivity : ComponentActivity() {
    private var tab by mutableStateOf("today")
    private val stack = mutableStateListOf<Route>()
    /** Bumped on resume so screens re-read permission state. */
    private var resumes by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Graph.init(this)
        setContent { DietTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        resumes++
        Nagger.schedule(this)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { WorkoutLink.pull(this@MainActivity) }
            runCatching { Health.pull(this@MainActivity) }
            runCatching { Nagger.check(this@MainActivity) }
        }
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
                    if (top != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Text(top.title, style = MaterialTheme.typography.titleLarge)
                    }
                    Box(Modifier.weight(1f)) {
                        when (top) {
                            null -> when (tab) {
                                "today" -> TodayScreen(d, onOpen = ::open)
                                "foods" -> FoodsScreen(d, onOpen = ::open)
                                "plan" -> PlanScreen(d)
                                "progress" -> ProgressScreen(d)
                                else -> SettingsScreen(d, resumes)
                            }
                            is Route.AddFood -> AddFoodScreen(d, top.date, top.slot, onOpen = ::open, onDone = { back() })
                            is Route.FoodEdit -> FoodEditScreen(d, top.id, onDone = { back() })
                            is Route.MealEdit -> MealEditScreen(d, top.id, onDone = { back() })
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val TABS = listOf(
            Triple("today", "Today", Icons.Filled.Today),
            Triple("foods", "Foods", Icons.Filled.Restaurant),
            Triple("plan", "Plan", Icons.Filled.CalendarMonth),
            Triple("progress", "Progress", Icons.Filled.MonitorWeight),
            Triple("settings", "Settings", Icons.Filled.Settings),
        )
    }
}
