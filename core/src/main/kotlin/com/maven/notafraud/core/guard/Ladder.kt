package com.maven.notafraud.core.guard

import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.plan.DayAdvisor
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.routine.Mood
import com.maven.notafraud.core.routine.RoutinePolicy
import com.maven.notafraud.core.workout.WorkoutLink

/**
 * The brick ladder (STATE.md § 22): the phone gets less usable the further the day drifts, and climbs back
 * down when you do things (not by waiting). Points come from today only, so every morning starts at 0.
 *
 *   0 normal · 1 friction (longer credit wait, purpose asked twice as often)
 *   2 dull (credits cost a longer wait, grayscale, unlocking lands on Now)
 *   3 brick (only the Level 3 list opens, credits frozen; emergencies and calls still work)
 *
 * Bedtime is Level 3 by itself (Level 2 until the list is set up).
 */
object Ladder {
    data class State(
        val level: Int,
        val points: Int,
        /** Why it's at this level, plainest first. */
        val reasons: List<String>,
        val bedtime: Boolean,
        /** What brings it down. */
        val wayDown: String,
    ) {
        val creditsFrozen: Boolean get() = level >= 3 || bedtime
    }

    val NAMES = listOf("Normal", "Friction", "Dull", "Brick")

    /** Maven's own apps: never bricked (they're how you get out). */
    val FAMILY = setOf("com.maven.notafraud", "com.maven.workout", "com.maven.diet")

    fun isBedtime(d: AppData, now: Long): Boolean {
        val l = d.settings.ladder
        if (!l.enabled || !l.bedtimeBrick) return false
        val m = Time.minuteOfDay(now, Time.zone(d.settings.zoneId))
        return if (l.bedMin <= l.wakeMin) m in l.bedMin until l.wakeMin else m >= l.bedMin || m < l.wakeMin
    }

    fun state(d: AppData, now: Long): State {
        val l = d.settings.ladder
        if (!l.enabled || com.maven.notafraud.core.model.Module.BLOCKING !in d.settings.modules) return State(0, 0, emptyList(), false, "")
        val top = if (l.allowlistConfirmed) l.maxLevel.coerceIn(0, 3) else l.maxLevel.coerceIn(0, 2)
        if (isBedtime(d, now)) {
            val lvl = minOf(3, if (l.allowlistConfirmed) 3 else 2)
            return State(lvl, 0, listOf("Bedtime: ${Time.clockText(l.bedMin)}–${Time.clockText(l.wakeMin)}"), true, "Morning (${Time.clockText(l.wakeMin)})")
        }
        // Doing the thing is the way out: a running session drops it to normal.
        d.active?.let { return State(0, 0, listOf("Working: ${it.title}"), false, "") }

        val zone = Time.zone(d.settings.zoneId)
        val today = Time.dateOf(now, zone)
        val m = Time.minuteOfDay(now, zone)
        var pts = 0
        val why = mutableListOf<String>()

        val slipped = DayAdvisor.skippedBlocks(d, now).coerceAtMost(3)
        if (slipped > 0) { pts += slipped; why += "$slipped planned block" + (if (slipped == 1) "" else "s") + " slipped" }

        val angry = RoutinePolicy.statuses(d, now).filter { it.mood == Mood.ANGRY }
        if (angry.isNotEmpty()) { pts += angry.size.coerceAtMost(2); why += "overdue: " + angry.take(3).joinToString { it.item.title.lowercase() } }

        val usage = d.usage.firstOrNull { it.date == today.toString() && it.device == "phone" }
        val wasted = usage?.minutesByApp?.filterKeys { it in d.rules.blocked }?.values?.sum() ?: 0
        val wastePts = when { wasted >= 150 -> 3; wasted >= 90 -> 2; wasted >= 45 -> 1; else -> 0 }
        if (wastePts > 0) { pts += wastePts; why += "${Time.durationText(wasted)} in blocked apps today" }

        if (WorkoutLink.mustToday(d, now) && m >= d.settings.workoutNagFromMin) {
            pts += if (m >= 18 * 60) 2 else 1
            why += "today's workout keeps the weekly minimum"
        }

        if (UnlockCheckIn.ignoring(d, now)) { pts += 1; why += "check-in put off ${d.checkIn.laters} times" }

        // Recent work buys points back: 25+ minutes logged in the last 90 minutes.
        val recent = d.sessions.filter { it.end > now - 90 * Time.MIN }.sumOf { it.minutes }
        if (recent >= 25 && pts > 0) { pts = (pts - 2).coerceAtLeast(0); why += "−2 for ${Time.durationText(recent)} of work just now" }

        var level = when { pts >= 6 -> 3; pts >= 4 -> 2; pts >= 2 -> 1; else -> 0 }.coerceAtMost(top)
        // Protected time (date night, Sunday off) never goes past friction.
        val protectedNow = d.todayPlan?.blocks?.any { it.protected && it.start <= now && now < it.end } == true
        if (protectedNow && level > 1) { level = 1; why += "protected time: capped at friction" }
        val down = buildList {
            if (slipped > 0 || pts > 0) add("start a task (any running session drops it to Normal)")
            if (angry.isNotEmpty()) add("do " + angry.first().item.title.lowercase())
            if (UnlockCheckIn.ignoring(d, now)) add("answer the check-in")
            if (WorkoutLink.mustToday(d, now)) add("start today's workout")
        }.joinToString("; ")
        return State(level, pts, why, false, down)
    }

    /** Opens at Level 3 / bedtime? Calls are handled before this (never blocked). */
    fun allowedAtTop(d: AppData, pkg: String, bedtime: Boolean): Boolean {
        val l = d.settings.ladder
        if (FAMILY.any { pkg.startsWith(it) } || pkg in GuardPolicy.SAFE || pkg in d.rules.alwaysAllowed) return true
        if (pkg in d.rules.focus) return true
        // Apps your routine opens (Bible, a reading app…) always open: they're how you get the item done.
        if (pkg in com.maven.notafraud.core.routine.RoutinePolicy.openApps(d)) return true
        return pkg in l.allowlist || (bedtime && pkg in l.bedtimeExtra)
    }

    /** Credit wait at this level (seconds). */
    fun creditWaitSec(base: Int, level: Int): Int = when (level) {
        0 -> base
        1 -> maxOf(base * 2, 30)
        else -> maxOf(base * 3, 60)
    }

    /** Purpose question comes back this often (minutes). */
    fun purposeEveryMin(base: Int, level: Int): Int = if (level >= 1) (base / 2).coerceAtLeast(2) else base

    /** Apps that usually have to keep working, offered first for the Level 3 list. */
    val ESSENTIALS = listOf(
        "com.google.android.apps.maps", "com.waze", "com.sec.android.app.camera", "com.google.android.GoogleCamera",
        "com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.samsung.android.calendar", "com.google.android.calendar",
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.duosecurity.duomobile", "com.okta.android.auth",
        "com.instructure.candroid", "com.google.android.gm", "com.microsoft.office.outlook", "com.microsoft.teams",
        "com.google.android.apps.docs", "com.samsung.android.app.notes", "com.google.android.keep", "com.android.chrome",
        "com.sec.android.app.sbrowser", "com.spotify.music", "com.samsung.android.spay", "com.google.android.apps.walletnfcrel",
        "com.sec.android.app.shealth", "com.google.android.apps.healthdata", "com.samsung.android.app.watchmanager", "com.samsung.android.calculator",
    )

    /**
     * Suggestions for the Level 3 list: known essentials that are installed, then what you use most that isn't blocked.
     * Returns package to a short why.
     */
    fun suggestions(d: AppData, installed: Set<String>, today: java.time.LocalDate): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        for (p in ESSENTIALS) if (p in installed && p !in d.rules.blocked) out[p] = "usually needed"
        val since = today.minusDays(7).toString()
        val used = HashMap<String, Int>()
        for (u in d.usage) if (u.device == "phone" && u.date >= since) for ((p, min) in u.minutesByApp) used[p] = (used[p] ?: 0) + min
        for ((p, min) in used.entries.sortedByDescending { it.value }) {
            if (p !in installed || p in d.rules.blocked || p in out || p in GuardPolicy.SAFE || FAMILY.any { p.startsWith(it) } || p.contains("launcher")) continue
            if (min / 7 < 3) continue
            out[p] = "~${Time.durationText(min / 7)} a day"
            if (out.size >= 30) break
        }
        return out.entries.map { it.key to it.value }
    }
}
