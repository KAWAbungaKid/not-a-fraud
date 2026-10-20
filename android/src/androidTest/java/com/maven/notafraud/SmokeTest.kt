package com.maven.notafraud

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maven.notafraud.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Launches the app and walks every tab. Catches crashes on real Android in CI. */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun everyTabRenders() {
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("RIGHT NOW").fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithText("WORKING").fetchSemanticsNodes().isNotEmpty())
        for (tab in listOf("Plan", "Tasks", "Inbox", "Setup", "Now")) {
            rule.onNodeWithText(tab).performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithText("Setup").performClick()
        rule.waitForIdle()
        assertTrue(rule.onAllNodesWithText("Setup ·", substring = true).fetchSemanticsNodes().isNotEmpty())
    }
}
