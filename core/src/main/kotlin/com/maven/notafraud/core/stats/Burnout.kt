package com.maven.notafraud.core.stats

import com.maven.notafraud.core.model.routineFor
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.plan.DayAdvisor
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.routine.Mood
import com.maven.notafraud.core.routine.RoutinePolicy

/** Signs of running low. Two or more → "How's today?" opens by itself (once a day). */
object Burnout {
    fun signals(d: AppData, now: Long): List<String> {
        val zone = Time.zone(d.settings.zoneId)
        val today = Time.dateOf(now, zone)
        val ctx = d.days.firstOrNull { it.date == today.toString() }
        val out = mutableListOf<String>()
        val nights = DayAdvisor.shortNights(d, now)
        if (nights >= 2) out += "$nights short nights in a row"
        else ctx?.sleepHours?.takeIf { it < d.settings.badSleepHours }?.let { out += "%.1fh sleep".format(it) }
        ctx?.sleepStart?.let { s -> if (Time.minuteOfDay(s, zone) in 30..(5 * 60)) out += "late night (asleep ${Time.clockAmPm(s, zone)})" }
        val skipped = DayAdvisor.skippedBlocks(d, now)
        if (skipped >= 2) out += "$skipped blocks slipped"
        val phoneToday = d.usage.firstOrNull { it.date == today.toString() && it.device == "phone" }?.minutesByApp?.values?.sum() ?: 0
        val past = d.usage.filter { it.device == "phone" && it.date < today.toString() }.takeLast(7).map { it.minutesByApp.values.sum() }
        if (past.size >= 3 && phoneToday > 120 && phoneToday > past.average() * 1.5) out += "phone ${Time.durationText(phoneToday)} (usually ${Time.durationText(past.average().toInt())})"
        val hungry = RoutinePolicy.statuses(d, now).filter { it.item.category == com.maven.notafraud.core.model.RoutineCategory.MEAL && it.mood == Mood.ANGRY }
        if (hungry.isNotEmpty()) out += "missed ${hungry.joinToString { it.item.title.lowercase() }}"
        com.maven.notafraud.core.diet.DietLink.today(d, now)?.let { s ->
            if (com.maven.notafraud.core.diet.DietLink.lowCheckIn(s)) out += "Twig check-in: clarity ${s.clarity ?: "?"}/5, energy ${s.energy ?: "?"}/5"
        }
        val w = d.routineFor(com.maven.notafraud.core.model.AutoCheck.WORKOUT)
        if (w != null && d.routineDays.isNotEmpty() && (0..3).none { RoutinePolicy.day(d, today.minusDays(it.toLong())).done.containsKey(w.id) }) out += "no workout in 3+ days"
        return out
    }

    fun shouldPrompt(d: AppData, now: Long): Boolean {
        val zone = Time.zone(d.settings.zoneId)
        val ctx = d.days.firstOrNull { it.date == Time.dateOf(now, zone).toString() }
        if (ctx?.mode != null || ctx?.promptedAt != null) return false
        if (Time.minuteOfDay(now, zone) < 8 * 60) return false
        return signals(d, now).size >= 2
    }
}
