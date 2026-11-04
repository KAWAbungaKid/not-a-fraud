package com.maven.notafraud.core.guard

/**
 * Medium lock (STATE.md § 22): the phone's own Settings pages that would switch Not a Fraud off or remove it.
 * The Android side reads the window's text and switches; this decides. Turning something ON is always allowed.
 */
object SettingsGuard {
    /** Apps whose screens can switch Not a Fraud off. */
    val PACKAGES = setOf(
        "com.android.settings", "com.samsung.accessibility", "com.samsung.android.lool",
        "com.google.android.packageinstaller", "com.android.packageinstaller", "com.samsung.android.packageinstaller",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller", "com.google.android.apps.healthdata",
    )

    data class Node(val text: String, val checkable: Boolean = false, val checked: Boolean = false, val top: Int = 0, val bottom: Int = 0)

    /** Buttons that remove or stop the app. */
    private val DANGER = listOf("uninstall", "force stop", "disable", "clear data", "clear storage", "deactivate", "do you want to uninstall")

    /** Does this screen let you switch [labels] off right now? */
    fun protects(nodes: List<Node>, labels: Set<String>): Boolean {
        val named = nodes.filter { n -> val t = n.text.trim(); labels.any { l -> t.equals(l, true) || t.equals("Use $l", true) || (t.contains(l, true) && t.length <= l.length + 40) } }
        if (named.isEmpty()) return false
        val lower = nodes.map { it.text.trim().lowercase() }
        if (lower.any { t -> DANGER.any { d -> t == d || t.startsWith(d) } }) return true
        val switches = nodes.filter { it.checkable }
        if (switches.none { it.checked }) return false
        // A page about this app alone (accessibility, notification / usage access, battery): any switch that's on.
        if (switches.size <= 3) return true
        // A list of many apps: only a switch on this app's own row.
        return named.any { l -> switches.any { s -> s.checked && (s.top + s.bottom) / 2 in (l.top - 24)..(l.bottom + 24) } }
    }
}
