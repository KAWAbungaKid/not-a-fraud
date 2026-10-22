package com.maven.notafraud

import android.content.Intent
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.maven.notafraud.guard.BlockActivity
import com.maven.notafraud.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlowTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun blockScreenShowsWaysOut() {
        val intent = Intent(ctx, BlockActivity::class.java)
            .putExtra(BlockActivity.EXTRA_PKG, "com.instagram.android")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<BlockActivity>(intent).use {
            rule.waitForIdle()
            assertTrue(rule.onAllNodesWithText("locked", substring = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue(rule.onAllNodesWithText("min banked", substring = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue(rule.onAllNodesWithText("Emergency: open now", substring = true).fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithText("10m").performClick()
            rule.waitForIdle()
            assertTrue(rule.onAllNodesWithText("Opening in", substring = true).fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithText("Never mind").performClick()
            rule.waitForIdle()
        }
    }

    @Test
    fun addTaskAppearsInList() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitForIdle()
            rule.onNodeWithText("Tasks").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("List").performClick()
            rule.waitForIdle()
            rule.onNodeWithContentDescription("Add task").performClick()
            rule.waitForIdle()
            rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(isDialog()))[0].performTextInput("Emulator test task")
            rule.onNodeWithText("Save").performClick()
            rule.waitForIdle()
            // The list is long and lazily composed, so check the store rather than scrolling.
            assertTrue(Graph.data.tasks.any { it.title == "Emulator test task" })
            rule.onNodeWithText("Plan").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Replan").performClick()
            rule.waitForIdle()
        }
    }

    @Test
    fun mapAndRoutineRender() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitForIdle()
            rule.onNodeWithText("Tasks").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Map").performClick()
            rule.waitForIdle()
            assertTrue(rule.onAllNodesWithText("Be a man of God").fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithText("Be a man of God").performClick()
            rule.waitForIdle()
            assertTrue(rule.onAllNodesWithText("Faith", substring = true).fetchSemanticsNodes().isNotEmpty())
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitForIdle()
            rule.onNodeWithText("You").performClick()
            rule.waitForIdle()
            assertTrue(rule.onAllNodesWithText("Wake up").fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithText("Stats").performClick()
            rule.waitForIdle()
            assertTrue(rule.onAllNodesWithText("Last 7 days", substring = true).fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithText("Skills").performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Climbing", substring = true).performClick()
            rule.waitForIdle()
            rule.onNodeWithText("Bouldering").performClick()
            rule.waitForIdle()
            assertTrue(rule.onAllNodesWithText("Plan practice").fetchSemanticsNodes().isNotEmpty())
        }
    }
}
