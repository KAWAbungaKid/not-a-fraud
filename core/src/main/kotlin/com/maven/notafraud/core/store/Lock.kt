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

    /** Attach the typed reason to a waiting change. */
    fun setReason(d: AppData, id: String, reason: String) =
        d.copy(pending = d.pending.map { if (it.id == id) it.copy(reason = reason.trim().ifEmpty { null } ?: "(no reason given)") else it })

    /** Morning window on the phone's Settings pages for Not a Fraud. */
    const val SETTINGS_WINDOW_MIN = 10

    fun settingsOpen(d: AppData, now: Long) = !d.settings.guardSettingsPages || !d.settings.settingsLock || d.settingsWindowUntil > now

    fun requestSettingsWindow(d: AppData, reason: String?, now: Long) = request(
        d, PendingChange(kind = ChangeKind.SETTINGS_WINDOW, label = "Open Not a Fraud's phone settings for $SETTINGS_WINDOW_MIN min", createdAt = now, applyAfter = now, reason = reason), now,
    )

    /** Ladder changes: anything that gives more access waits; anything stricter applies now. */
    fun setLadder(d: AppData, new: com.maven.notafraud.core.model.LadderSettings, label: String, now: Long): AppData {
        val old = d.settings.ladder
        val looser = (old.enabled && !new.enabled) || new.maxLevel < old.maxLevel || (old.bedtimeBrick && !new.bedtimeBrick) ||
            bedLength(new) < bedLength(old) || new.grayscaleFrom > old.grayscaleFrom || new.unlockToNowFrom > old.unlockToNowFrom ||
            (old.allowlistConfirmed && (!(new.allowlist - old.allowlist).isEmpty() || !(new.bedtimeExtra - old.bedtimeExtra).isEmpty()))
        val c = PendingChange(kind = ChangeKind.LADDER, label = label, createdAt = now, applyAfter = now, text = LADDER_JSON.encodeToString(com.maven.notafraud.core.model.LadderSettings.serializer(), new))
        if (!looser) return apply(d, c).copy(pending = d.pending.filterNot { it.kind == ChangeKind.LADDER })
        // Stricter parts now: removals from the lists, higher levels, longer bedtime.
        val strictNow = old.copy(
            allowlist = old.allowlist.intersect(new.allowlist),
            bedtimeExtra = old.bedtimeExtra.intersect(new.bedtimeExtra),
            maxLevel = maxOf(old.maxLevel, new.maxLevel),
            grayscaleFrom = minOf(old.grayscaleFrom, new.grayscaleFrom),
            unlockToNowFrom = minOf(old.unlockToNowFrom, new.unlockToNowFrom),
            lockWallpaper = new.lockWallpaper,
        )
        return request(d.copy(settings = d.settings.copy(ladder = strictNow)), c, now)
    }

    private fun bedLength(l: com.maven.notafraud.core.model.LadderSettings) = if (!l.bedtimeBrick) 0 else ((l.wakeMin - l.bedMin + 24 * 60) % (24 * 60))
    private val LADDER_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Deleting or switching off a routine item waits like any other loosening. */
    fun removeRoutine(d: AppData, itemId: String, delete: Boolean, now: Long): AppData {
        val item = d.routine.firstOrNull { it.id == itemId } ?: return d
        return request(d, PendingChange(kind = ChangeKind.ROUTINE_REMOVE, label = (if (delete) "Delete" else "Switch off") + " routine: ${item.title}", createdAt = now, applyAfter = now, pkg = itemId, flag = delete), now)
    }

    /**
     * Modules: switching one on is immediate; switching Blocking or Routine off loosens things, so it waits for the
     * morning like any other loosening change. Planner / Stats / Skills just hide, so they switch off right away.
     */
    fun setModule(d: AppData, m: com.maven.notafraud.core.model.Module, on: Boolean, now: Long): AppData {
        val c = PendingChange(kind = ChangeKind.MODULE_OFF, label = "Switch off ${m.label}", createdAt = now, applyAfter = now, pkg = m.name)
        if (on) return d.copy(settings = d.settings.copy(modules = d.settings.modules + m), pending = d.pending.filterNot { it.kind == ChangeKind.MODULE_OFF && it.pkg == m.name })
        val strict = m == com.maven.notafraud.core.model.Module.BLOCKING || m == com.maven.notafraud.core.model.Module.ROUTINE
        return if (strict) request(d, c, now) else apply(d, c)
    }

    fun apply(d: AppData, c: PendingChange): AppData = when (c.kind) {
        ChangeKind.MODULE_OFF -> runCatching { com.maven.notafraud.core.model.Module.valueOf(c.pkg ?: "") }.getOrNull()
            ?.let { m -> d.copy(settings = d.settings.copy(modules = d.settings.modules - m)) } ?: d
        ChangeKind.SETTINGS_WINDOW -> d.copy(settingsWindowUntil = System.currentTimeMillis() + SETTINGS_WINDOW_MIN * Time.MIN)
        ChangeKind.SETTINGS_GUARD -> d.copy(settings = d.settings.copy(guardSettingsPages = c.flag ?: true))
        ChangeKind.CHECKIN -> d.copy(settings = d.settings.copy(checkInOnUnlock = c.flag ?: true))
        ChangeKind.LADDER -> c.text?.let { runCatching { LADDER_JSON.decodeFromString(com.maven.notafraud.core.model.LadderSettings.serializer(), it) }.getOrNull() }
            ?.let { d.copy(settings = d.settings.copy(ladder = it)) } ?: d
        ChangeKind.LADDER_ALLOW -> d.copy(settings = d.settings.copy(ladder = d.settings.ladder.copy(allowlist = d.settings.ladder.allowlist + c.items)))
        ChangeKind.ROUTINE_REMOVE -> if (c.flag == true) d.copy(routine = d.routine.filterNot { it.id == c.pkg })
            else d.copy(routine = d.routine.map { if (it.id == c.pkg) it.copy(enabled = false) else it })
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
