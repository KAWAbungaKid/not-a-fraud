package com.maven.notafraud.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.media.AudioManager
import com.maven.notafraud.Graph
import com.maven.notafraud.core.guard.Credits
import com.maven.notafraud.core.guard.GuardAction
import com.maven.notafraud.core.guard.GuardPolicy
import com.maven.notafraud.core.guard.NagPolicy
import com.maven.notafraud.core.guard.Sites
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.notify.Notifier
import com.maven.notafraud.notify.RoutineAlerts
import com.maven.notafraud.sense.AppLabels
import com.maven.notafraud.core.store.Actions

/**
 * The persistent host (STATE.md section 5). The system keeps accessibility services alive, so this
 * owns foreground-app detection, blocking, credit earning and the nag chain — no foreground
 * service, no wakelocks, no timers except while a watched app is actually open.
 *
 * Websites: while a known browser is in front (and only then) the service also listens for content
 * changes and reads the address bar, so "instagram.com in Chrome" is blocked like the Instagram app.
 * The subject being judged is then "site:instagram.com" instead of the browser's package.
 */
class GuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    /** What's being judged right now: a package name, or "site:<domain>". */
    private var currentPkg: String? = null
    private var foregroundPkg: String? = null
    private var enteredAt = 0L
    private var currentAction = GuardAction.IGNORE
    private val pending = mutableListOf<Runnable>()
    private var lastUrlCheck = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    leave(); currentPkg = null; foregroundPkg = null
                    bedtimeCheck()
                }
                Intent.ACTION_POWER_CONNECTED -> bedtimeCheck()
                Intent.ACTION_USER_PRESENT -> {
                    val n = System.currentTimeMillis()
                    Graph.update { d -> Actions.autoFromHealth(Actions.recordFirstUnlock(d, n), Graph.today()) }
                    rootInActiveWindow?.packageName?.toString()?.let { onForeground(it) }
                }
            }
        }
    }

    /** Plugging in or turning the screen off late in the evening looks like going to bed. */
    private fun bedtimeCheck() {
        val m = Time.minuteOfDay(System.currentTimeMillis(), Graph.zone)
        if (m >= 21 * 60 || m < 2 * 60) RoutineAlerts.check(this, bedtime = true)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Graph.init(this)
        instance = this
        lastConnectedAt = System.currentTimeMillis()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
        })
        audio = getSystemService(AudioManager::class.java)
        runCatching { audio?.addOnModeChangedListener(mainExecutor, modeListener) }
        Notifier.cancelAlert(this)
        Notifier.forgetLast()
        Graph.refresh(this)
    }

    private var audio: AudioManager? = null
    private val modeListener = AudioManager.OnModeChangedListener { reevaluate() }

    /** On any call (phone, Snapchat, FaceTime-style, WhatsApp): never block, never nudge. */
    private fun inCall(): Boolean {
        val m = audio?.mode ?: return false
        return m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE ||
            m == AudioManager.MODE_CALL_SCREENING
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastEventAt = System.currentTimeMillis()
                if (pkg in KEYBOARDS) onTyping()
                onForeground(pkg)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> if (pkg == foregroundPkg && pkg in Sites.BROWSERS) checkUrl(pkg)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> if (keyboardUp()) onTyping()
        }
    }

    private fun onForeground(pkg: String) {
        if (pkg in TRANSPARENT || pkg in KEYBOARDS) return
        if (pkg == foregroundPkg) return
        foregroundPkg = pkg
        browserInFront = pkg in Sites.BROWSERS
        updateEvents()
        if (pkg in Sites.BROWSERS) {
            // Judge the browser itself until the address bar says otherwise.
            switchTo(pkg)
            checkUrl(pkg, force = true)
            return
        }
        switchTo(pkg)
    }

    private fun switchTo(subject: String) {
        if (subject == currentPkg) return
        leave()
        currentPkg = subject
        enteredAt = System.currentTimeMillis()
        evaluate(subject)
        Graph.refresh(this)
    }

    /** Read the browser's address bar; judge the site if it's on the list, otherwise the browser. */
    private fun checkUrl(browser: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUrlCheck < 700) return
        lastUrlCheck = now
        // The bar can vanish while scrolling; no reading means "no change", not "left the site".
        val text = runCatching { addressBar(browser) }.getOrNull() ?: return
        val site = Sites.match(text, Graph.data.rules.blockedSites)
        switchTo(site?.let { Sites.PREFIX + it } ?: browser)
    }

    private fun addressBar(browser: String): String? {
        val root = rootInActiveWindow ?: return null
        for (id in Sites.URL_BAR_IDS[browser].orEmpty()) {
            val nodes: List<AccessibilityNodeInfo> = root.findAccessibilityNodeInfosByViewId(id).orEmpty()
            val t = nodes.firstOrNull()?.text?.toString()
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    private var browserInFront = false
    private var watchingKeyboard = false
    private var appliedEvents = -1

    /**
     * Extra events only while needed: content changes while a browser is in front (address bar),
     * window changes while a free window is open (keyboard up = still texting). Otherwise just app switches.
     */
    private fun updateEvents() {
        val types = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            (if (browserInFront) AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else 0) or
            (if (watchingKeyboard) AccessibilityEvent.TYPE_WINDOWS_CHANGED else 0)
        if (types == appliedEvents) return
        appliedEvents = types
        val info = serviceInfo ?: return
        info.eventTypes = types
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.notificationTimeout = if (browserInFront) 400 else 250
        serviceInfo = info
    }

    private fun keyboardUp(): Boolean = runCatching { windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)

    /** Typing inside a free window keeps it open. */
    private fun onTyping() {
        val pkg = currentPkg ?: return
        val d = Graph.data
        if (!d.rules.profile(pkg).extendWhileTyping) return
        val until = d.ledger.freeWindowUntil[pkg] ?: return
        val now = System.currentTimeMillis()
        if (until < now) return
        val after = Graph.update { x -> x.copy(ledger = Credits.extendWhileTyping(x.ledger, pkg, now)) }
        val newUntil = after.ledger.freeWindowUntil[pkg] ?: until
        if (newUntil > until) {
            cancelPending()
            post(newUntil - now + 500) { if (currentPkg == pkg) reevaluate() }
        }
    }

    /** Re-check the app in front (after an unlock expires, or credits change). */
    fun reevaluate() {
        val pkg = currentPkg ?: return
        cancelPending()
        evaluate(pkg)
    }

    private fun evaluate(pkg: String) {
        val now = System.currentTimeMillis()
        val inFreeWindow = (Graph.data.ledger.freeWindowUntil[pkg] ?: 0) > now
        watchingKeyboard = inFreeWindow
        updateEvents()
        if (inCall()) { currentAction = GuardAction.IGNORE; Notifier.cancelNag(this); return }
        val decision = GuardPolicy.decide(pkg, Graph.data, now, packageName.removeSuffix(".dev"))
        currentAction = decision.action
        when (decision.action) {
            GuardAction.BLOCK -> {
                // Still typing when the window runs out: keep it open instead of cutting the conversation.
                if (Graph.data.ledger.freeWindowUntil[pkg]?.let { now - it < 60_000 } == true && keyboardUp() && Graph.data.rules.profile(pkg).extendWhileTyping) {
                    Graph.update { x -> x.copy(ledger = Credits.freeWindow(x.ledger, pkg, 2, now, "Still typing")) }
                    reevaluate()
                    return
                }
                block(pkg)
            }
            GuardAction.UNLOCKED -> {
                if (!inFreeWindow) scheduleNags(pkg)
                post(maxOf(1_000L, decision.unlockedUntil - now + 500)) { if (currentPkg == pkg) reevaluate() }
            }
            GuardAction.EARN, GuardAction.IGNORE -> Unit
        }
    }

    private fun block(pkg: String) {
        Notifier.cancelNag(this)
        // Give a notification tap a moment to open its reply window first (the two arrive together).
        post(700) {
            if (currentPkg != pkg) return@post
            val d = GuardPolicy.decide(pkg, Graph.data, System.currentTimeMillis(), packageName.removeSuffix(".dev"))
            if (d.action != GuardAction.BLOCK || inCall()) { evaluate(pkg); return@post }
            showBlock(pkg)
        }
    }

    private fun showBlock(pkg: String) {
        // For a site, step the browser back off the page so it isn't sitting there behind the block screen.
        if (pkg.startsWith(Sites.PREFIX)) performGlobalAction(GLOBAL_ACTION_BACK)
        startActivity(
            Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(BlockActivity.EXTRA_PKG, pkg)
                .putExtra(BlockActivity.EXTRA_RETURN_TO, foregroundPkg)
        )
    }

    /** Close the current segment: bank focus credits, stop nags. */
    private fun leave() {
        val pkg = currentPkg
        val now = System.currentTimeMillis()
        if (pkg != null && currentAction == GuardAction.EARN) {
            val minutes = ((now - enteredAt) / 60_000.0).coerceAtMost(180.0)
            if (minutes >= 0.5) {
                val label = AppLabels.label(this, pkg)
                Graph.update { d ->
                    d.copy(ledger = Credits.earn(d.ledger, d.settings, minutes, d.settings.earnPerFocusMin, "Focus: $label", now))
                }
            }
        }
        if (currentAction == GuardAction.UNLOCKED) Notifier.cancelNag(this)
        cancelPending()
        currentAction = GuardAction.IGNORE
    }

    private fun scheduleNags(pkg: String) {
        val s = Graph.data.settings
        if (!s.nagsEnabled) return
        val rough = Graph.data.todayPlan?.roughDay == true
        val label = AppLabels.label(this, pkg)
        val since = System.currentTimeMillis() - enteredAt
        NagPolicy.offsetsMin(rough).forEachIndexed { level, offset ->
            post(offset * Time.MIN - since) {
                if (currentPkg != pkg || inCall()) return@post
                val st = Graph.nowState()
                if (st.protected) return@post
                val mins = ((System.currentTimeMillis() - enteredAt) / Time.MIN).toInt()
                val nag = NagPolicy.nag(level, label, mins, st.suggested?.title, Graph.data.settings.creed, rough)
                Notifier.postNag(this, nag, st.suggested?.id)
            }
        }
    }

    private fun post(delayMs: Long, block: () -> Unit) {
        val r = Runnable(block)
        pending += r
        handler.postDelayed(r, delayMs.coerceAtLeast(0))
    }

    private fun cancelPending() {
        pending.forEach(handler::removeCallbacks)
        pending.clear()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { audio?.removeOnModeChangedListener(modeListener) }
        leave()
        runCatching { unregisterReceiver(screenReceiver) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: GuardService? = null
            private set
        @Volatile var lastEventAt = 0L
        @Volatile var lastConnectedAt = 0L

        val KEYBOARDS = setOf("com.samsung.android.honeyboard", "com.google.android.inputmethod.latin", "com.touchtype.swiftkey")

        /** Windows that float over the app without replacing it. */
        private val TRANSPARENT = setOf(
            "com.android.systemui",
            "com.samsung.android.honeyboard",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.app.cocktailbarservice",
            "com.samsung.android.app.smartcapture",
        )
    }
}
