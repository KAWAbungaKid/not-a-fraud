package com.maven.notafraud.core.guard

import com.maven.notafraud.core.model.AppRules

/** PC side: decides whether the foreground window is a distraction, by process name or window title. */
object DesktopPolicy {
    /** Processes that are never distractions even if a title matches (this app, the shell, lock screen). */
    val SAFE = setOf("notafraud.exe", "explorer.exe", "lockapp.exe", "searchhost.exe", "shellexperiencehost.exe", "startmenuexperiencehost.exe")

    fun isDistraction(process: String, title: String, rules: AppRules): Boolean {
        val p = process.lowercase()
        if (p in SAFE) return false
        if (p in rules.desktopBlockedProcesses) return true
        val t = title.lowercase()
        return rules.desktopBlockedTitles.any { kw -> kw.isNotBlank() && t.contains(kw.lowercase()) }
    }

    /** Human label for a usage row: "chrome.exe" -> "chrome". */
    fun label(process: String): String = process.substringAfterLast('\\').substringAfterLast('/').removeSuffix(".exe").removeSuffix(".EXE")
}
