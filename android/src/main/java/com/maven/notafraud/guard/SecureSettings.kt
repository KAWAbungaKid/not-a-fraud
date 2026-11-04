package com.maven.notafraud.guard

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Things only possible after the one-time computer step (STATE.md § 22):
 *   adb shell pm grant com.maven.notafraud android.permission.WRITE_SECURE_SETTINGS
 * It lets the app switch grayscale and turn its own guard back on. Nothing else, no root, no data access.
 */
object SecureSettings {
    private const val DALTONIZER_ON = "accessibility_display_daltonizer_enabled"
    private const val DALTONIZER = "accessibility_display_daltonizer"
    /** Android's value for "monochromacy" (grayscale). */
    private const val MONOCHROME = 0

    fun granted(ctx: Context) = ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun command(ctx: Context) = "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"

    fun grayscaleOn(ctx: Context): Boolean = runCatching {
        Settings.Secure.getInt(ctx.contentResolver, DALTONIZER_ON, 0) == 1 && Settings.Secure.getInt(ctx.contentResolver, DALTONIZER, -1) == MONOCHROME
    }.getOrDefault(false)

    /**
     * Grayscale on or off. The ladder only turns OFF grayscale it turned on itself, so a morning routine that
     * turns it on (Samsung Modes and Routines) is never undone.
     */
    fun setGrayscale(ctx: Context, on: Boolean): Boolean {
        if (!granted(ctx)) return false
        val prefs = ctx.getSharedPreferences("ladder", Context.MODE_PRIVATE)
        return runCatching {
            if (on) {
                if (grayscaleOn(ctx)) return true
                Settings.Secure.putInt(ctx.contentResolver, DALTONIZER, MONOCHROME)
                Settings.Secure.putInt(ctx.contentResolver, DALTONIZER_ON, 1)
                prefs.edit().putBoolean("grayByUs", true).apply()
            } else if (prefs.getBoolean("grayByUs", false)) {
                Settings.Secure.putInt(ctx.contentResolver, DALTONIZER_ON, 0)
                prefs.edit().putBoolean("grayByUs", false).apply()
            }
            true
        }.getOrDefault(false)
    }

    /** The guard got switched off (by Android or by hand): switch it back on. */
    fun reEnableGuard(ctx: Context): Boolean {
        if (!granted(ctx)) return false
        return runCatching {
            val me = ComponentName(ctx, GuardService::class.java).flattenToString()
            val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            if (cur.split(':').none { it.equals(me, ignoreCase = true) }) {
                Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (cur.isBlank()) me else "$cur:$me")
            }
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }
}
