package com.maven.notafraud.guard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.maven.notafraud.Graph
import com.maven.notafraud.core.guard.Credits
import com.maven.notafraud.core.guard.GuardAction
import com.maven.notafraud.core.guard.GuardPolicy
import com.maven.notafraud.core.guard.NagPolicy
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.notify.Notifier
import com.maven.notafraud.sense.AppLabels

/**
 * The persistent host (STATE.md section 5). The system keeps accessibility services alive, so this
 * owns foreground-app detection, blocking, credit earning and the nag chain — no foreground
 * service, no wakelocks, no timers except while a watched app is actually open.
 */
class GuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentPkg: String? = null
    private var enteredAt = 0L
    private var currentAction = GuardAction.IGNORE
    private val pending = mutableListOf<Runnable>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { leave(); currentPkg = null }
                Intent.ACTION_USER_PRESENT -> rootInActiveWindow?.packageName?.toString()?.let { onForeground(it) }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Graph.init(this)
        instance = this
        lastConnectedAt = System.currentTimeMillis()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        Notifier.cancelAlert(this)
        Notifier.forgetLast()
        Graph.refresh(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        lastEventAt = System.currentTimeMillis()
        onForeground(pkg)
    }

    private fun onForeground(pkg: String) {
        if (pkg in TRANSPARENT || pkg == currentPkg) return
        leave()
        currentPkg = pkg
        enteredAt = System.currentTimeMillis()
        evaluate(pkg)
        Graph.refresh(this)
    }

    /** Re-check the app in front (after an unlock expires, or credits change). */
    fun reevaluate() {
        val pkg = currentPkg ?: return
        cancelPending()
        evaluate(pkg)
    }

    private fun evaluate(pkg: String) {
        val now = System.currentTimeMillis()
        val decision = GuardPolicy.decide(pkg, Graph.data, now, packageName.removeSuffix(".dev"))
        currentAction = decision.action
        when (decision.action) {
            GuardAction.BLOCK -> block(pkg)
            GuardAction.UNLOCKED -> {
                scheduleNags(pkg)
                post(maxOf(1_000L, decision.unlockedUntil - now + 500)) { if (currentPkg == pkg) reevaluate() }
            }
            GuardAction.EARN, GuardAction.IGNORE -> Unit
        }
    }

    private fun block(pkg: String) {
        Notifier.cancelNag(this)
        startActivity(
            Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(BlockActivity.EXTRA_PKG, pkg)
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
                if (currentPkg != pkg) return@post
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
