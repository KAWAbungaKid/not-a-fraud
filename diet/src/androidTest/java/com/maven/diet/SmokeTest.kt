package com.maven.diet

import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.maven.diet.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Logs a food, water and a check-in, then opens every screen. Screenshots go to ci-logs/diet-shots. */
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        rule.waitForIdle()
        runCatching {
            val bmp = rule.onAllNodes(isRoot()).onFirst().captureToImage().asAndroidBitmap()
            val dir = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "shots").apply { mkdirs() }
            java.io.FileOutputStream(java.io.File(dir, "$name.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun scrollTo(text: String) {
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(text, substring = true))
        rule.waitForIdle()
    }

    @Test
    fun logAndOpenEverything() {
        rule.waitForIdle()
        shot("01-today")
        scrollTo("+ Add")
        rule.onAllNodesWithText("+ Add").onFirst().performClick()
        rule.waitForIdle()
        shot("02-add-food")
        rule.onAllNodesWithText("Chicken breast, raw").onFirst().performClick()
        rule.waitForIdle()
        shot("03-servings")
        rule.onNode(hasText("Log") and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        scrollTo("+16")
        rule.onNodeWithText("+16").performClick()
        scrollTo("Brain fog")
        rule.onAllNodesWithText("3").onFirst().performClick()
        rule.onAllNodesWithText("4")[1].performClick()
        rule.waitForIdle()
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText("Today"))
        shot("04-today-logged")
        scrollTo("Brain fog")
        shot("05-today-checkin")

        rule.onNodeWithText("Foods").performClick()
        shot("06-foods")
        rule.onNodeWithText("+ Food from label").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Calories").assertExists()
        shot("07-label")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithText("Chicken & rice bowl (example)").performClick()
        rule.waitForIdle()
        shot("08-recipe")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

        rule.onNodeWithText("Progress").performClick()
        shot("09-progress")
        rule.onNodeWithText("Settings").performClick()
        shot("10-settings")
        scrollTo("Connections")
        shot("11-settings-connections")
    }

    @Test
    fun syncProviderAnswers() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val json = ctx.contentResolver.query(Uri.parse("content://${ctx.packageName}.sync/status"), null, null, null, null)!!
            .use { c -> c.moveToFirst(); c.getString(0) }
        assertTrue(json, json.contains("\"kcalTarget\"") && json.contains("\"nags\""))
    }
}
