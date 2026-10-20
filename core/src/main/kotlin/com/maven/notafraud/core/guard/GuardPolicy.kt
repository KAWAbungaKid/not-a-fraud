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

data class GuardDecision(val action: GuardAction, val unlockedUntil: Long = 0L)

object GuardPolicy {

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
    )

    fun decide(pkg: String, data: AppData, now: Long, ownPackagePrefix: String = "com.maven.notafraud"): GuardDecision {
        val rules = data.rules
        if (pkg.startsWith(ownPackagePrefix) || pkg in SAFE || pkg in rules.alwaysAllowed) return GuardDecision(GuardAction.IGNORE)
        if (pkg in rules.focus) return GuardDecision(GuardAction.EARN)
        if (!rules.blockingEnabled || pkg !in rules.blocked) return GuardDecision(GuardAction.IGNORE)
        return if (Credits.isUnlocked(data.ledger, pkg, now)) {
            GuardDecision(GuardAction.UNLOCKED, Credits.unlockedUntil(data.ledger, pkg))
        } else {
            GuardDecision(GuardAction.BLOCK)
        }
    }
}
