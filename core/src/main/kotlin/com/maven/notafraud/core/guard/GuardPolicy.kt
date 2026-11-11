package com.maven.notafraud.core.guard

import com.maven.notafraud.core.model.AppData

enum class GuardAction {
    /** Not a watched app. */
    IGNORE,
    /** Focus app: earns credits while in front. */
    EARN,
    /** Blocked app, currently paid for: allowed, nag chain runs. */
    UNLOCKED,
    /** Blocked app, no unlock: show the block screen. */
    BLOCK,
}

/** [brick]: blocked because of the ladder's Level 3 / bedtime, not because the app is on the blocked list. */
data class GuardDecision(val action: GuardAction, val unlockedUntil: Long = 0L, val brick: Boolean = false)

object GuardPolicy {

    /**
     * Android's own pickers that open on top of whatever app asked: the photo picker ("Photos & videos"), the file
     * picker, the share sheet. They're part of the app you're using (attaching homework photos at bedtime), never
     * blocked or counted as their own app.
     */
    val SYSTEM_PICKERS: Set<String> = setOf(
        "com.google.android.providers.media.module",
        "com.android.providers.media.module",
        "com.google.android.photopicker",
        "com.android.providers.media",
        "com.google.android.documentsui",
        "com.android.documentsui",
        "com.android.intentresolver",
        "com.google.android.intentresolver",
        "com.samsung.android.app.sharelive",
    )

    /** Never blocked, never counted: system UI, launchers, phone, settings, input methods. */
    val SAFE: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.samsung.android.dialer",
        "com.google.android.dialer",
        "com.android.phone",
        "com.samsung.android.incallui",
        "com.samsung.android.messaging",
        "com.google.android.apps.messaging",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.samsung.android.honeyboard",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.app.aodservice",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
    ) + SYSTEM_PICKERS


    fun decide(pkg: String, data: AppData, now: Long, ownPackagePrefix: String = "com.maven.notafraud"): GuardDecision {
        val rules = data.rules
        // Blocking switched off as a module: the guard only watches (calls, usage), never blocks.
        if (com.maven.notafraud.core.model.Module.BLOCKING !in data.settings.modules) return GuardDecision(GuardAction.IGNORE)
        if (pkg.startsWith(Sites.PREFIX)) {
            if (!rules.blockingEnabled || Sites.label(pkg) !in rules.blockedSites) return GuardDecision(GuardAction.IGNORE)
            return if (Credits.isUnlocked(data.ledger, pkg, now)) GuardDecision(GuardAction.UNLOCKED, Credits.unlockedUntil(data.ledger, pkg))
            else GuardDecision(GuardAction.BLOCK)
        }
        if (pkg.startsWith(ownPackagePrefix) || pkg in SAFE || pkg in rules.alwaysAllowed) return GuardDecision(GuardAction.IGNORE)
        // The ladder at the top (or bedtime): only the Level 3 list opens. Emergency unlocks still count.
        if (rules.blockingEnabled) {
            val lad = Ladder.state(data, now)
            if (lad.level >= 3 && !Ladder.allowedAtTop(data, pkg, lad.bedtime)) {
                return if (Credits.isUnlocked(data.ledger, pkg, now)) GuardDecision(GuardAction.UNLOCKED, Credits.unlockedUntil(data.ledger, pkg), brick = true)
                else GuardDecision(GuardAction.BLOCK, brick = true)
            }
        }
        if (pkg in rules.focus) return GuardDecision(GuardAction.EARN)
        if (!rules.blockingEnabled || pkg !in rules.blocked) return GuardDecision(GuardAction.IGNORE)
        return if (Credits.isUnlocked(data.ledger, pkg, now)) {
            GuardDecision(GuardAction.UNLOCKED, Credits.unlockedUntil(data.ledger, pkg))
        } else {
            GuardDecision(GuardAction.BLOCK)
        }
    }
}
