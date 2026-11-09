package com.maven.notafraud.guard

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.maven.notafraud.notify.Notifier
import com.maven.notafraud.sense.UsageReader

/**
 * Is the guard really working, not just switched on? Android can leave an accessibility service switched on but
 * unbound (after a crash, or Samsung putting the app to sleep): the switch says on, nothing gets blocked.
 * Checked hourly and when the app opens. With the computer step done it restarts itself; otherwise it says so.
 */
object GuardHealth {
    enum class State { RUNNING, OFF, NOT_RUNNING, NOT_HEARING }

    /** Apps changed this recently but the guard heard nothing for this long = it's deaf. */
    private const val DEAF_MIN = 20

    fun state(ctx: Context): State {
        if (!com.maven.notafraud.sense.Perms.accessibility(ctx)) return State.OFF
        val g = GuardService.instance ?: return State.NOT_RUNNING
        val heard = maxOf(GuardService.lastAnyEventAt, GuardService.lastConnectedAt)
        val now = System.currentTimeMillis()
        if (now - heard > DEAF_MIN * 60_000L) {
            val used = runCatching { UsageReader.lastResumeAt(ctx, heard) }.getOrNull()
            if (used != null && used > heard + 60_000L) return State.NOT_HEARING
        }
        return if (g === GuardService.instance) State.RUNNING else State.NOT_RUNNING
    }

    fun describe(ctx: Context): String = when (state(ctx)) {
        State.RUNNING -> "Running" + GuardService.lastAnyEventAt.takeIf { it > 0 }?.let { " · last heard ${GuardLog.time(it)}" }.orEmpty()
        State.OFF -> "OFF: the accessibility switch for Not a Fraud is off"
        State.NOT_RUNNING -> "NOT RUNNING: switched on, but Android isn't running it"
        State.NOT_HEARING -> "NOT HEARING: running, but hasn't seen an app change since ${GuardLog.time(maxOf(GuardService.lastAnyEventAt, GuardService.lastConnectedAt))}"
    }

    /** Switch it off and on again (needs the computer step). */
    fun restart(ctx: Context): Boolean {
        if (!SecureSettings.granted(ctx)) return false
        return runCatching {
            val me = ComponentName(ctx, GuardService::class.java).flattenToString()
            val cur = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val without = cur.split(':').filter { it.isNotBlank() && !it.equals(me, ignoreCase = true) }.joinToString(":")
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without)
            Thread.sleep(800)
            Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, if (without.isBlank()) me else "$without:$me")
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }

    /** Hourly: give a just-started process a moment to bind, then fix or tell. */
    suspend fun check(ctx: Context) {
        var s = state(ctx)
        if (s == State.NOT_RUNNING) { kotlinx.coroutines.delay(10_000); s = state(ctx) }
        if (s == State.RUNNING || s == State.OFF) return
        GuardLog.init(ctx)
        if (restart(ctx)) {
            GuardLog.add("Guard", "was ${s.name.lowercase().replace('_', ' ')}: restarted it")
            return
        }
        GuardLog.add("Guard", "is ${s.name.lowercase().replace('_', ' ')}: asked you to restart it")
        Notifier.postAlert(
            ctx, "Blocking stopped working",
            "The guard is switched on but Android isn't running it. Tap, then in Accessibility switch Not a Fraud off and on again. " +
                "(The computer step on the Brick ladder page lets it do this by itself.)",
            tab = "blocking",
        )
    }
}
