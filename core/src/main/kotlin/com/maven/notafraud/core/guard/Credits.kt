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
}
