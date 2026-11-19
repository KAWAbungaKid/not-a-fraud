package com.maven.notafraud.core.guard

import com.maven.notafraud.core.diet.DietLink
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.CheckInState
import com.maven.notafraud.core.model.Excuse
import com.maven.notafraud.core.model.Module
import com.maven.notafraud.core.plan.DayReview
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.routine.Mood
import com.maven.notafraud.core.routine.RoutinePolicy

/**
 * The unlock check-in (STATE.md § 27). Feedback 2026-11-10: "making it easier to get things inputted that I
 * haven't done rather than just keep putting off, then at the end of the day 'I have so many things to enter'."
 *
 * Shown on every unlock while something is overdue (never during a call): late routine items (with "open the
 * app" for Bible etc.), Twig weigh-in and meals (typed right there), missed plan blocks ("what did you do
 * instead?") and the priority nag. "Later" hides it for 20 minutes; three Laters in a row add a ladder point
 * until one is answered. It never blocks anything itself: Later is always there.
 */
object UnlockCheckIn {
    enum class Kind { ROUTINE, MEAL, WEIGH, BLOCK, PRIORITY }

    data class Prompt(
        val kind: Kind,
        /** Handled-today key ("routine:<id>", "block:<start>", "meal:<slot>", "weigh", "priority:<taskId>"). */
        val key: String,
        val title: String,
        val text: String,
        val itemId: String? = null,
        val openPkg: String? = null,
        val slot: String? = null,
        val usual: String? = null,
        val blockStart: Long = 0,
        val blockEnd: Long = 0,
        val taskId: String? = null,
    )

    private fun handledToday(d: AppData, now: Long): Set<String> =
        d.checkIn.handled[Time.dateOf(now, Time.zone(d.settings.zoneId)).toString()].orEmpty()

    fun prompts(d: AppData, now: Long): List<Prompt> {
        val zone = Time.zone(d.settings.zoneId)
        val done = handledToday(d, now)
        val out = mutableListOf<Prompt>()
        val diet = DietLink.today(d, now)
        val quick = diet?.acceptsQuick == true

        val nagsPaused = Pauses.covers(d, now, com.maven.notafraud.core.model.PauseCover.NAGS)
        if (Module.ROUTINE in d.settings.modules && !nagsPaused) for (st in RoutinePolicy.statuses(d, now)) {
            if (st.mood != Mood.LATE && st.mood != Mood.ANGRY) continue
            val it = st.item
            if ("routine:${it.id}" in done) continue
            val late = "${Time.durationText(st.minutesLate)} late"
            if (quick && it.dietSlot != null && DietLink.owns(d, it)) {
                out += Prompt(Kind.MEAL, "meal:${it.dietSlot}", it.title, "$late · what did you eat?", itemId = it.id, slot = it.dietSlot, usual = diet?.usual?.get(it.dietSlot))
            } else {
                out += Prompt(Kind.ROUTINE, "routine:${it.id}", it.title, late, itemId = it.id, openPkg = it.openPkg)
            }
        }
        // Twig reminders with no routine item of their own.
        for (n in DietLink.extraNags(d, now)) {
            when {
                n.kind == "WEIGH_IN" && quick && "weigh" !in done -> out += Prompt(Kind.WEIGH, "weigh", "Weigh-in", n.text.ifBlank { "Not weighed in yet today." })
                n.kind == "MEAL" && quick -> {
                    val slot = n.key.removePrefix("meal:")
                    if ("meal:$slot" !in done && out.none { it.key.equals("meal:$slot", true) })
                        out += Prompt(Kind.MEAL, "meal:$slot", slot, n.text.ifBlank { "What did you eat?" }, slot = slot, usual = diet?.usual?.get(slot))
                }
            }
        }
        if (Module.PLANNER in d.settings.modules) {
            // Only the last few hours: older misses go to the end-of-day summary, not the lock screen.
            for (b in DayReview.missed(d, now).filter { it.end > now - 6 * Time.HOUR }.sortedByDescending { it.start }.take(3)) {
                val key = "block:${b.start}"
                if (key in done) continue
                out += Prompt(Kind.BLOCK, key, b.title, "${Time.clock(b.start, zone)}–${Time.clock(b.end, zone)} · what did you do instead?",
                    blockStart = b.start, blockEnd = b.end, taskId = b.taskId)
            }
            DayReview.priorityNag(d, now)?.let { n ->
                if ("priority:${n.task.id}" !in done) out += Prompt(Kind.PRIORITY, "priority:${n.task.id}", "You said this mattered", n.text, taskId = n.task.id)
            }
        }
        return out
    }

    /** Show it on this unlock? */
    fun due(d: AppData, now: Long): Boolean {
        if (!d.settings.checkInOnUnlock) return false
        if (now < d.checkIn.snoozeUntil) return false
        if (Pauses.covers(d, now, com.maven.notafraud.core.model.PauseCover.CHECKIN)) return false
        if (Ladder.isBedtime(d, now)) return false
        return prompts(d, now).isNotEmpty()
    }

    private fun today(d: AppData, now: Long) = Time.dateOf(now, Time.zone(d.settings.zoneId)).toString()

    /** Later: hidden for checkInLaterMin; counts toward the ladder point. */
    fun later(d: AppData, now: Long): AppData {
        val t = today(d, now)
        val c = d.checkIn
        val n = if (c.laterDate == t) c.laters + 1 else 1
        return d.copy(checkIn = c.copy(snoozeUntil = now + d.settings.checkInLaterMin * Time.MIN, laters = n, laterDate = t))
    }

    /** One prompt answered (or waved off with a reason): resets the Later count. */
    fun handled(d: AppData, key: String, now: Long): AppData {
        val t = today(d, now)
        val c = d.checkIn
        val keep = c.handled.filterKeys { it >= Time.dateOf(now, Time.zone(d.settings.zoneId)).minusDays(2).toString() }
        return d.copy(checkIn = c.copy(laters = 0, handled = keep + (t to (keep[t].orEmpty() + key))))
    }

    /** "I did something else / nothing" for a missed block. [counts] false = it was nothing (still missed for the score). */
    fun excuse(d: AppData, start: Long, end: Long, reason: String, counts: Boolean, now: Long): AppData {
        val date = Time.dateOf(start, Time.zone(d.settings.zoneId)).toString()
        val ctx = d.days.firstOrNull { it.date == date } ?: com.maven.notafraud.core.model.DayContext(date)
        val x = d.copy(days = d.days.filterNot { it.date == date } + ctx.copy(excused = ctx.excused + Excuse(start, end, reason.trim().ifBlank { "other" }, counts)))
        return handled(x, "block:$start", now)
    }

    /** Ladder: three Laters in a row today. */
    fun ignoring(d: AppData, now: Long): Boolean = d.checkIn.laterDate == today(d, now) && d.checkIn.laters >= 3

    fun initial(): CheckInState = CheckInState()
}
