package com.maven.notafraud.core.guard

import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.Pause
import com.maven.notafraud.core.model.PauseCover
import com.maven.notafraud.core.plan.Time

/**
 * Planned unlocks (STATE.md § 28). Feedback 2026-11-19: new apps for vacation kept getting kicked out mid-login and
 * changes couldn't be made until tomorrow. "Unlock from now until December 3rd at 5:15 PM", chosen covers each time,
 * and not something to switch on in a weak moment: setting one up means typing a long phrase (no pasting) and a
 * reason. Ending one early is always instant; extending means a new one (phrase again).
 */
object Pauses {
    /** Longest single pause. */
    const val MAX_DAYS = 31

    fun active(d: AppData, now: Long): List<Pause> = d.pauses.filter { it.start <= now && now < (it.endedAt ?: it.end) }

    fun covers(d: AppData, now: Long, c: PauseCover): Boolean = d.pauses.any { c in it.covers && it.start <= now && now < (it.endedAt ?: it.end) }

    /** Upcoming (start later) and active, soonest first. */
    fun current(d: AppData, now: Long): List<Pause> = d.pauses.filter { now < (it.endedAt ?: it.end) }.sortedBy { it.start }

    sealed interface Result {
        data class Ok(val data: AppData, val pause: Pause) : Result
        data class No(val why: String) : Result
    }

    fun create(d: AppData, start: Long, end: Long, covers: Set<PauseCover>, reason: String, typed: String, now: Long): Result {
        val s = maxOf(start, now)
        return when {
            covers.isEmpty() -> Result.No("Pick at least one thing to switch off.")
            end <= s + Time.MIN -> Result.No("The end has to be after the start.")
            end - s > MAX_DAYS * Time.DAY -> Result.No("At most $MAX_DAYS days at a time.")
            reason.trim().length < 10 -> Result.No("Say why (a sentence).")
            !Credits.phraseMatches(typed, d.settings.pausePhrase) -> Result.No("The phrase doesn't match yet.")
            else -> {
                val p = Pause(start = s, end = end, covers = covers, reason = reason.trim(), createdAt = now)
                Result.Ok(d.copy(pauses = d.pauses + p), p)
            }
        }
    }

    /** End now (or cancel one that hasn't started). Tightening: always instant. */
    fun end(d: AppData, id: String, now: Long) = d.copy(pauses = d.pauses.map { if (it.id == id && it.endedAt == null) it.copy(endedAt = maxOf(it.start, now)) else it })

    fun text(p: Pause, zone: java.time.ZoneId, now: Long): String {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", java.util.Locale.US)
        fun t(x: Long) = java.time.Instant.ofEpochMilli(x).atZone(zone).format(fmt)
        val what = p.covers.sortedBy { it.ordinal }.joinToString { it.label.lowercase() }
        return if (p.start > now) "From ${t(p.start)} to ${t(p.end)}: $what off" else "Until ${t(p.end)}: $what off"
    }
}
