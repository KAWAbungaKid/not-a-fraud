package com.maven.notafraud

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.maven.notafraud.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the app and walks every tab. Catches crashes on real Android in CI. */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = androidx.compose.ui.test.junit4.createEmptyComposeRule()

    @Test
    fun onboardingFinishes() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        Graph.init(ctx)
        Graph.update { com.maven.notafraud.core.seed.Templates.fresh(System.currentTimeMillis(), java.time.ZoneId.systemDefault().id) }
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use {
            rule.waitForIdle()
            rule.onNodeWithText("Get started").performClick(); rule.waitForIdle()
            rule.onNodeWithText("Get fit and strong").performClick(); rule.waitForIdle()
            for (i in 0 until 5) { rule.onNodeWithText("Next").performClick(); rule.waitForIdle() }
            rule.onNodeWithText("Start").performClick(); rule.waitForIdle()
            assertTrue(Graph.data.settings.onboarded)
            assertTrue(Graph.data.routine.any { it.title == "Workout" })
            assertTrue(rule.onAllNodesWithText("RIGHT NOW").fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun everyTabRenders() {
        TestData.loadFull()
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { tabs() }
    }

    private fun tabs() {
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("RIGHT NOW").fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("WORKING").fetchSemanticsNodes().isNotEmpty())
        for (tab in listOf("Plan", "Routine", "Tasks", "More", "Now")) {
            rule.onNodeWithText(tab).performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithText("More").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Setup checks").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Setup ·", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
