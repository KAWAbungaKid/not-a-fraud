package com.maven.notafraud.core.plan

import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.DayMode

/**
 * "How's today?" suggestion. Rules for now (STATE.md section 11); later versions learn from which
 * choice actually led to a productive evening.
 *
 * - Nap only before 3pm (later naps wreck the night): 20 min after one short night, 90 min after two or more.
 * - After 3pm with short sleep or skipped blocks: reset rather than nap.
 * - Otherwise push.
 */
object DayAdvisor {
    data class Advice(val mode: DayMode, val rechargeMin: Int, val reason: String)

    fun shortNights(d: AppData, now: Long): Int {
        val zone = Time.zone(d.settings.zoneId)
        val today = Time.dateOf(now, zone)
        var n = 0
        for (i in 0..2) {
            val h = d.days.firstOrNull { it.date == today.minusDays(i.toLong()).toString() }?.sleepHours ?: break
            if (h < d.settings.badSleepHours) n++ else break
        }
        return n
    }

    /** Task blocks in today's plan that ended with no work logged and no reason given (DayReview.missed). */
    fun skippedBlocks(d: AppData, now: Long): Int = DayReview.missed(d, now).size

    fun suggest(d: AppData, now: Long): Advice {
        val zone = Time.zone(d.settings.zoneId)
        val m = Time.minuteOfDay(now, zone)
        val nights = shortNights(d, now)
        val skipped = skippedBlocks(d, now)
        val beforeThree = m < 15 * 60
        val sleep = d.days.firstOrNull { it.date == Time.dateOf(now, zone).toString() }?.sleepHours
        val sleepText = sleep?.let { "%.1fh sleep".format(it) } ?: "no sleep data"
        // Not a Twig: brain fog / low energy in today's check-in, and the basics behind it.
        val diet = com.maven.notafraud.core.diet.DietLink.today(d, now)
        val foggy = com.maven.notafraud.core.diet.DietLink.lowCheckIn(diet)
        val basics = diet?.let { s ->
            listOfNotNull(
                s.hoursSinceFood?.takeIf { it >= 5 && s.inEatingWindow }?.let { "eat (%.0f h since food)".format(it) },
                (s.waterTargetOz - s.waterOz).takeIf { s.waterTargetOz > 0 && it > s.waterTargetOz / 3 }?.let { "drink water ($it oz behind)" },
            )
        }.orEmpty()
        val basicsText = if (basics.isEmpty()) "" else " First: " + basics.joinToString(", ") + "."
        return when {
            foggy && nights == 0 && beforeThree -> Advice(DayMode.RECHARGE, 20, "Your check-in says foggy / low.$basicsText Then a 20-minute daylight walk, then push.")
            foggy && nights == 0 -> Advice(DayMode.RESET, 30, "Your check-in says foggy / low.$basicsText Then easy wins and protect tonight's sleep.")
            nights >= 2 && beforeThree -> Advice(DayMode.RECHARGE, 90, "$nights short nights in a row. A 90-minute nap now, then a lighter plan.")
            nights >= 1 && beforeThree -> Advice(DayMode.RECHARGE, 20, "$sleepText. A 20-minute nap or walk, then push.")
            (nights >= 1 || skipped >= 2) && !beforeThree -> Advice(DayMode.RESET, 45, "It's late for a nap. Keep what's due, do easy wins, protect tonight's sleep.")
            skipped >= 2 -> Advice(DayMode.PUSH, 0, "$skipped blocks slipped. Short 25-minute blocks, easiest win first.")
            else -> Advice(DayMode.PUSH, 0, "You're fine. Short blocks get you moving.")
        }
    }
}
