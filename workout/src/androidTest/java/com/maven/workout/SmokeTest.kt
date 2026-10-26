package com.maven.workout

import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.maven.workout.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opens every screen once and runs a workout start → finish with nothing ticked. Catches crashes, not layout. */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    /** Saves a screenshot to the app's external files dir; CI pulls them to branch ci-logs/workout-shots. */
    private fun shot(name: String) {
        rule.waitForIdle()
        runCatching {
            val bmp = rule.onAllNodes(androidx.compose.ui.test.isRoot()).onFirst().captureToImage().asAndroidBitmap()
            val dir = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
            java.io.FileOutputStream(java.io.File(dir, "$name.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun back() {
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    @Test
    fun everyScreenOpens() {
        rule.waitForIdle()
        shot("01-today")
        rule.onAllNodesWithText("Start", substring = true).onFirst().assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Bench").assertExists()
        shot("02-workout")
        rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Done")).onFirst().performClick()
        shot("03-workout-ticked")
        rule.onAllNodesWithText("Finish").onFirst().performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Finish") and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        shot("04-summary")
        back()
        shot("05-today-after")

        rule.onNodeWithText("History").performClick()
        shot("06-history")
        rule.onNodeWithText("Exercises").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Bench").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Archived (hidden from pickers)").assertExists()
        shot("07-exercise")
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Training max", substring = true))
        shot("08-exercise-strength")
        back()

        rule.onNodeWithText("Program").performClick()
        shot("09-program")
        rule.onNodeWithText("Edit program").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Weeks per block").assertExists()
        back()
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Bench (main)"))
        rule.onNodeWithText("Bench (main)").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Week 1").onFirst().assertExists()
        shot("10-type")
        back()

        rule.onNodeWithText("Settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Daily check when the minimum is at risk").performScrollTo().assertIsDisplayed()
        shot("11-settings")
    }

    @Test
    fun syncProviderAnswers() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("content://${ctx.packageName}.sync/status")
        val json = ctx.contentResolver.query(uri, null, null, null, null)!!.use { c -> c.moveToFirst(); c.getString(0) }
        assertTrue(json, json.contains("\"minPerWeek\"") && json.contains("\"risk\""))
    }
}
