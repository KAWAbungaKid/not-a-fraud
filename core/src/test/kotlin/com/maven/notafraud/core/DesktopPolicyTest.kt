package com.maven.notafraud.core

import com.maven.notafraud.core.guard.DesktopPolicy
import com.maven.notafraud.core.model.AppRules
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopPolicyTest {
    private val rules = AppRules(desktopBlockedProcesses = setOf("steam.exe"))

    @Test
    fun `youtube in chrome is a distraction, docs are not`() {
        assertTrue(DesktopPolicy.isDistraction("chrome.exe", "Lofi beats - YouTube - Google Chrome", rules))
        assertFalse(DesktopPolicy.isDistraction("chrome.exe", "Heat transfer notes - Google Docs", rules))
    }

    @Test
    fun `blocked process matches case-insensitively and explorer is safe`() {
        assertTrue(DesktopPolicy.isDistraction("Steam.exe", "Store", rules))
        assertFalse(DesktopPolicy.isDistraction("explorer.exe", "YouTube folder", rules))
    }
}
