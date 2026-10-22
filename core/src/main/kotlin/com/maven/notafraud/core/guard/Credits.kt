package com.maven.notafraud.core.guard

import com.maven.notafraud.core.model.Ledger
import com.maven.notafraud.core.model.LedgerEntry
import com.maven.notafraud.core.model.Settings
import com.maven.notafraud.core.plan.Time
import kotlin.math.min

/**
 * Earn-to-unlock ledger. Minutes spent in focus apps or logged on tasks earn credit minutes;
 * credit minutes unlock a blocked app for that long. All functions are pure.
 */
object Credits {
    const val ALL = "*"
    private const val MAX_ENTRIES = 300

    fun earn(ledger: Ledger, settings: Settings, minutes: Double, rate: Double, reason: String, now: Long): Ledger {
        if (minutes <= 0) return ledger
        val gain = min(minutes * rate, settings.maxBalanceMin - ledger.balanceMin).coerceAtLeast(0.0)
        if (gain <= 0.0) return ledger
        return ledger.copy(
            balanceMin = ledger.balanceMin + gain,
            entries = appendMerged(ledger.entries, LedgerEntry(now, gain, reason)),
        )
    }

    fun canSpend(ledger: Ledger, minutes: Int) = ledger.balanceMin >= minutes - 1e-6

    /** Unlocks [pkg] for [minutes], paid from the balance. Returns null if the balance is short. */
    fun spend(ledger: Ledger, pkg: String, minutes: Int, label: String, now: Long): Ledger? {
        if (minutes <= 0 || !canSpend(ledger, minutes)) return null
        val from = maxOf(now, ledger.unlockedUntil[pkg] ?: 0L)
        return ledger.copy(
            balanceMin = ledger.balanceMin - minutes,
            unlockedUntil = ledger.unlockedUntil + (pkg to from + minutes * Time.MIN),
            entries = (ledger.entries + LedgerEntry(now, -minutes.toDouble(), "Unlocked $label")).takeLast(MAX_ENTRIES),
        )
    }

    fun emergencyUsesToday(ledger: Ledger, startOfToday: Long) = ledger.emergencyUses.count { it >= startOfToday }

    fun canEmergency(ledger: Ledger, settings: Settings, startOfToday: Long) =
        emergencyUsesToday(ledger, startOfToday) < settings.emergencyDailyLimit

    /** Free short unlock with friction (wait + typed phrase, enforced by the UI). Logged, limited per day. */
    fun emergency(ledger: Ledger, settings: Settings, pkg: String, label: String, now: Long, startOfToday: Long): Ledger? {
        if (!canEmergency(ledger, settings, startOfToday)) return null
        return ledger.copy(
            unlockedUntil = ledger.unlockedUntil + (pkg to now + settings.emergencyUnlockMin * Time.MIN),
            emergencyUses = (ledger.emergencyUses + now).filter { it > now - 2 * Time.DAY },
            entries = (ledger.entries + LedgerEntry(now, 0.0, "Emergency unlock: $label")).takeLast(MAX_ENTRIES),
        )
    }

    fun isUnlocked(ledger: Ledger, pkg: String, now: Long) =
        (ledger.unlockedUntil[pkg] ?: 0L) > now || (ledger.unlockedUntil[ALL] ?: 0L) > now

    fun unlockedUntil(ledger: Ledger, pkg: String): Long =
        maxOf(ledger.unlockedUntil[pkg] ?: 0L, ledger.unlockedUntil[ALL] ?: 0L)

    /** End an unlock early (user left the app and chose to bank nothing; or relocking). */
    fun relock(ledger: Ledger, pkg: String): Ledger = ledger.copy(unlockedUntil = ledger.unlockedUntil - pkg)

    fun prune(ledger: Ledger, now: Long): Ledger =
        ledger.copy(unlockedUntil = ledger.unlockedUntil.filterValues { it > now })

    /** Consecutive earn entries with the same reason within 5 minutes merge, so the log stays readable. */
    private fun appendMerged(entries: List<LedgerEntry>, e: LedgerEntry): List<LedgerEntry> {
        val last = entries.lastOrNull()
        return if (last != null && last.reason == e.reason && e.at - last.at < 5 * Time.MIN && last.deltaMin > 0) {
            entries.dropLast(1) + last.copy(at = e.at, deltaMin = last.deltaMin + e.deltaMin)
        } else {
            (entries + e).takeLast(MAX_ENTRIES)
        }
    }

    // ---- Free windows (v0.3) -------------------------------------------------------------

    /** Free, logged access for [minutes] (reply window, quick-open, still texting). */
    fun freeWindow(ledger: Ledger, pkg: String, minutes: Int, now: Long, reason: String): Ledger {
        val until = maxOf(ledger.unlockedUntil[pkg] ?: 0L, now + minutes * Time.MIN)
        return ledger.copy(
            unlockedUntil = ledger.unlockedUntil + (pkg to until),
            freeWindowUntil = ledger.freeWindowUntil + (pkg to until),
            entries = (ledger.entries + LedgerEntry(now, 0.0, reason)).takeLast(MAX_ENTRIES),
        )
    }

    fun quickOpensToday(ledger: Ledger, pkg: String, startOfToday: Long) = ledger.quickOpens[pkg].orEmpty().count { it >= startOfToday }

    fun quickOpen(ledger: Ledger, profile: com.maven.notafraud.core.model.BlockProfile, pkg: String, label: String, now: Long, startOfToday: Long): Ledger? {
        if (!profile.quickOpen || quickOpensToday(ledger, pkg, startOfToday) >= profile.quickOpenPerDay) return null
        val l = freeWindow(ledger, pkg, profile.quickOpenMin, now, "Quick open: $label")
        return l.copy(quickOpens = l.quickOpens + (pkg to (l.quickOpens[pkg].orEmpty().filter { it > now - 2 * Time.DAY } + now)))
    }

    /** Typing inside a free window keeps it open at least [minutes] more. No-op outside a free window. */
    fun extendWhileTyping(ledger: Ledger, pkg: String, now: Long, minutes: Int = 2): Ledger {
        val until = ledger.freeWindowUntil[pkg] ?: return ledger
        if (until < now) return ledger
        val target = now + minutes * Time.MIN
        if (target <= until) return ledger
        return ledger.copy(unlockedUntil = ledger.unlockedUntil + (pkg to target), freeWindowUntil = ledger.freeWindowUntil + (pkg to target))
    }

    /** A free window ended within the last 10 minutes: the block screen offers "still texting?". */
    fun canStillTexting(ledger: Ledger, pkg: String, now: Long): Boolean {
        val until = ledger.freeWindowUntil[pkg] ?: return false
        return until <= now + 30_000 && now - until < 10 * Time.MIN
    }

    /** Instant emergency (no wait, no phrase), limited per app per day. */
    fun emergencyInstant(ledger: Ledger, settings: Settings, profile: com.maven.notafraud.core.model.BlockProfile, pkg: String, label: String, now: Long, startOfToday: Long): Ledger? {
        if (emergencyUsesToday(ledger, startOfToday) >= profile.emergencyPerDay) return null
        return ledger.copy(
            unlockedUntil = ledger.unlockedUntil + (pkg to now + settings.emergencyUnlockMin * Time.MIN),
            emergencyUses = (ledger.emergencyUses + now).filter { it > now - 2 * Time.DAY },
            entries = (ledger.entries + LedgerEntry(now, 0.0, "Emergency unlock: $label")).takeLast(MAX_ENTRIES),
        )
    }

    /** A message notification arrived for [pkg] (someone wrote to you). */
    fun messageArrived(ledger: Ledger, pkg: String, at: Long) = ledger.copy(lastMessageAt = ledger.lastMessageAt + (pkg to at))

    /**
     * Reply window: opening the app within [withinMin] of a new message (by tapping the notification or
     * any other way) is free for the profile's reply minutes, once per new message.
     */
    fun replyWindowIfMessage(ledger: Ledger, profile: com.maven.notafraud.core.model.BlockProfile, pkg: String, label: String, now: Long, withinMin: Int = 10): Ledger? {
        if (profile.replyWindowMin <= 0) return null
        val msg = ledger.lastMessageAt[pkg] ?: return null
        if (now - msg > withinMin * Time.MIN) return null
        if ((ledger.messageWindowUsed[pkg] ?: 0L) >= msg) return null
        return freeWindow(ledger, pkg, profile.replyWindowMin, now, "Reply window: $label")
            .let { it.copy(messageWindowUsed = it.messageWindowUsed + (pkg to msg)) }
    }
}
