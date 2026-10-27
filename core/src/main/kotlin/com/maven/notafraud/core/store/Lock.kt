package com.maven.notafraud.core.store

import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.BlockProfile
import com.maven.notafraud.core.model.ChangeKind
import com.maven.notafraud.core.model.PendingChange
import com.maven.notafraud.core.plan.Time

/**
 * Settings lock: a change that makes blocking looser (switching it off, unblocking an app, longer windows,
 * more emergencies, turning the lock itself off…) doesn't happen now. It waits until the next morning
 * ([Settings.lockReleaseMin]) and then asks "still want this?". Stricter changes apply at once.
 */
object Lock {

    /** Next morning's release time, at least 6 hours away (an urge at 1am still waits for the morning). */
    fun releaseAt(d: AppData, now: Long): Long {
        val zone = Time.zone(d.settings.zoneId)
        var day = Time.dateOf(now, zone)
        var t = Time.at(day, d.settings.lockReleaseMin, zone)
        while (t < now + 6 * Time.HOUR) { day = day.plusDays(1); t = Time.at(day, d.settings.lockReleaseMin, zone) }
        return t
    }

    /** Apply now if the lock is off, else queue it (replacing an older pending change of the same kind and app). */
    fun request(d: AppData, change: PendingChange, now: Long): AppData {
        if (!d.settings.settingsLock) return apply(d, change)
        val c = change.copy(createdAt = now, applyAfter = releaseAt(d, now))
        return d.copy(pending = d.pending.filterNot { it.kind == c.kind && it.pkg == c.pkg } + c)
    }

    fun due(d: AppData, now: Long) = d.pending.filter { it.applyAfter <= now }

    fun confirm(d: AppData, id: String): AppData {
        val c = d.pending.firstOrNull { it.id == id } ?: return d
        return apply(d.copy(pending = d.pending - c), c)
    }

    fun discard(d: AppData, id: String) = d.copy(pending = d.pending.filterNot { it.id == id })

    fun markNotified(d: AppData, ids: Set<String>) = d.copy(pending = d.pending.map { if (it.id in ids) it.copy(notified = true) else it })

    fun apply(d: AppData, c: PendingChange): AppData = when (c.kind) {
        ChangeKind.BLOCKING -> d.copy(rules = d.rules.copy(blockingEnabled = c.flag ?: true))
        ChangeKind.NAGS -> d.copy(settings = d.settings.copy(nagsEnabled = c.flag ?: true))
        ChangeKind.ROUTINE_NAGS -> d.copy(settings = d.settings.copy(routineNagsEnabled = c.flag ?: true))
        ChangeKind.UNBLOCK_APPS -> d.copy(rules = d.rules.copy(blocked = d.rules.blocked - c.items))
        ChangeKind.UNBLOCK_SITES -> d.copy(rules = d.rules.copy(blockedSites = d.rules.blockedSites - c.items))
        ChangeKind.FOCUS_APPS -> d.copy(rules = d.rules.copy(focus = d.rules.focus + c.items))
        ChangeKind.PROFILE -> {
            val p = c.profile ?: return d
            if (c.pkg.isNullOrEmpty()) d.copy(rules = d.rules.copy(defaultProfile = p))
            else d.copy(rules = d.rules.copy(profiles = d.rules.profiles + (c.pkg to p)))
        }
        ChangeKind.EMERGENCY_MIN -> d.copy(settings = d.settings.copy(emergencyUnlockMin = c.number ?: d.settings.emergencyUnlockMin))
        ChangeKind.EMERGENCY_PHRASE -> d.copy(settings = d.settings.copy(emergencyPhrase = c.text ?: d.settings.emergencyPhrase))
        ChangeKind.LOCK -> d.copy(settings = d.settings.copy(settingsLock = c.flag ?: true))
        ChangeKind.LOCK_TIME -> d.copy(settings = d.settings.copy(lockReleaseMin = c.number ?: d.settings.lockReleaseMin))
        ChangeKind.WORKOUT_PAUSE -> d.copy(settings = d.settings.copy(workoutPausesCredits = c.flag ?: true))
        ChangeKind.NUMBER -> d.copy(settings = withNumber(d.settings, c.text ?: "", c.number ?: return d))
    }

    /** Does [new] give more access than [old]? */
    fun loosens(old: BlockProfile, new: BlockProfile): Boolean =
        new.replyWindowMin > old.replyWindowMin ||
            (new.quickOpen && !old.quickOpen) || new.quickOpenMin > old.quickOpenMin || new.quickOpenPerDay > old.quickOpenPerDay ||
            (new.extendWhileTyping && !old.extendWhileTyping) ||
            new.creditWaitSec < old.creditWaitSec ||
            new.emergencyPerDay > old.emergencyPerDay || new.extraEmergencyPerDay > old.extraEmergencyPerDay ||
            (new.askPurpose && !old.askPurpose) || new.purposeCheckMin > old.purposeCheckMin || new.purposeDailyMin > old.purposeDailyMin

    /** Profile edit: stricter parts now, the whole thing in the morning if anything loosens. */
    fun setProfile(d: AppData, pkg: String, new: BlockProfile, label: String, now: Long): AppData {
        val old = if (pkg.isEmpty()) d.rules.defaultProfile else d.rules.profile(pkg)
        val change = PendingChange(kind = ChangeKind.PROFILE, label = "Looser rules for $label", createdAt = now, applyAfter = now, pkg = pkg, profile = new)
        if (!loosens(old, new)) return apply(d, change)
        // Apply the parts that tighten right away; keep the looser value until the morning.
        val strictNow = old.copy(
            replyWindowMin = minOf(old.replyWindowMin, new.replyWindowMin),
            quickOpen = old.quickOpen && new.quickOpen,
            quickOpenMin = minOf(old.quickOpenMin, new.quickOpenMin),
            quickOpenPerDay = minOf(old.quickOpenPerDay, new.quickOpenPerDay),
            extendWhileTyping = old.extendWhileTyping && new.extendWhileTyping,
            creditWaitSec = maxOf(old.creditWaitSec, new.creditWaitSec),
            emergencyPerDay = minOf(old.emergencyPerDay, new.emergencyPerDay),
            extraEmergencyPerDay = minOf(old.extraEmergencyPerDay, new.extraEmergencyPerDay),
            askPurpose = old.askPurpose && new.askPurpose,
            purposeCheckMin = minOf(old.purposeCheckMin, new.purposeCheckMin),
            purposeDailyMin = minOf(old.purposeDailyMin, new.purposeDailyMin),
        )
        return request(apply(d, change.copy(profile = strictNow)), change, now)
    }

    /** Blocked apps / sites / focus apps: additions now, removals (or focus additions) in the morning. */
    fun setBlocked(d: AppData, next: Set<String>, now: Long, label: (String) -> String): AppData {
        val removed = d.rules.blocked - next
        val x = d.copy(rules = d.rules.copy(blocked = d.rules.blocked + next))
        if (removed.isEmpty()) return x
        return request(x, PendingChange(kind = ChangeKind.UNBLOCK_APPS, label = "Unblock " + removed.joinToString { label(it) }, createdAt = now, applyAfter = now, items = removed), now)
    }

    fun setSites(d: AppData, next: Set<String>, now: Long): AppData {
        val removed = d.rules.blockedSites - next
        val x = d.copy(rules = d.rules.copy(blockedSites = d.rules.blockedSites + next))
        if (removed.isEmpty()) return x
        return request(x, PendingChange(kind = ChangeKind.UNBLOCK_SITES, label = "Unblock " + removed.joinToString(), createdAt = now, applyAfter = now, items = removed), now)
    }

    fun setFocus(d: AppData, next: Set<String>, now: Long, label: (String) -> String): AppData {
        val added = next - d.rules.focus
        val x = d.copy(rules = d.rules.copy(focus = d.rules.focus.intersect(next)))
        if (added.isEmpty()) return x
        return request(x, PendingChange(kind = ChangeKind.FOCUS_APPS, label = "Let " + added.joinToString { label(it) } + " earn credits", createdAt = now, applyAfter = now, items = added), now)
    }

    /** On/off switches where "off" is the loose direction. */
    fun setSwitch(d: AppData, kind: ChangeKind, on: Boolean, label: String, now: Long): AppData {
        val c = PendingChange(kind = kind, label = label, createdAt = now, applyAfter = now, flag = on)
        return if (on) apply(d, c).copy(pending = d.pending.filterNot { it.kind == kind }) else request(d, c, now)
    }

    /** Number settings (More → Planning → Your day and numbers). Keys are field names. */
    fun number(s: com.maven.notafraud.core.model.Settings, key: String): Int = when (key) {
        "dayStartMin" -> s.dayStartMin; "dayEndMin" -> s.dayEndMin; "maxChunkMin" -> s.maxChunkMin; "breakMin" -> s.breakMin
        "badSleepTenths" -> (s.badSleepHours * 10).toInt(); "roughFocusCapMin" -> s.roughFocusCapMin
        "stillTextingMin" -> s.stillTextingMin; "messageWindowMin" -> s.messageWindowMin; "maxBalanceMin" -> s.maxBalanceMin.toInt()
        "emergencyUnlockMin" -> s.emergencyUnlockMin; "angryRepeatMin" -> s.angryRepeatMin; "workTargetMin" -> s.workTargetMin
        "mindTargetMin" -> s.mindTargetMin; "sportMin" -> s.sportMin; "suggestBlockMin" -> s.suggestBlockMin
        else -> 0
    }

    fun withNumber(s: com.maven.notafraud.core.model.Settings, key: String, v: Int): com.maven.notafraud.core.model.Settings = when (key) {
        "dayStartMin" -> s.copy(dayStartMin = v); "dayEndMin" -> s.copy(dayEndMin = v); "maxChunkMin" -> s.copy(maxChunkMin = v); "breakMin" -> s.copy(breakMin = v)
        "badSleepTenths" -> s.copy(badSleepHours = v / 10.0); "roughFocusCapMin" -> s.copy(roughFocusCapMin = v)
        "stillTextingMin" -> s.copy(stillTextingMin = v); "messageWindowMin" -> s.copy(messageWindowMin = v); "maxBalanceMin" -> s.copy(maxBalanceMin = v.toDouble())
        "emergencyUnlockMin" -> s.copy(emergencyUnlockMin = v); "angryRepeatMin" -> s.copy(angryRepeatMin = v); "workTargetMin" -> s.copy(workTargetMin = v)
        "mindTargetMin" -> s.copy(mindTargetMin = v); "sportMin" -> s.copy(sportMin = v); "suggestBlockMin" -> s.copy(suggestBlockMin = v)
        else -> s
    }

    /** Raising these gives more access, so it waits for the morning. */
    private val LOOSER_WHEN_HIGHER = setOf("stillTextingMin", "messageWindowMin", "maxBalanceMin", "emergencyUnlockMin", "angryRepeatMin")

    fun setNumber(d: AppData, key: String, v: Int, label: String, now: Long): AppData {
        val c = PendingChange(kind = ChangeKind.NUMBER, label = label, createdAt = now, applyAfter = now, pkg = key, text = key, number = v)
        return if (key in LOOSER_WHEN_HIGHER && v > number(d.settings, key)) request(d, c, now) else apply(d, c).copy(pending = d.pending.filterNot { it.kind == ChangeKind.NUMBER && it.pkg == key })
    }
}
