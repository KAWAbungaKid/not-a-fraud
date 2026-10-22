package com.maven.notafraud.sense

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.maven.notafraud.Graph
import com.maven.notafraud.core.guard.Credits

/**
 * Reply windows: tapping a blocked app's notification (someone messaged you) opens it free for a few
 * minutes (BlockProfile.replyWindowMin). Opening the same app from the home screen is still blocked.
 * (v0.2 mirrored message text into an Inbox; Snapchat's notifications rarely carry the text, so that's gone.)
 */
class MirrorService : NotificationListenerService() {

    override fun onListenerConnected() {
        Graph.init(this)
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn ?: return
        if (reason != REASON_CLICK) return
        Graph.init(this)
        val pkg = sbn.packageName
        val d = Graph.data
        if (pkg !in d.rules.blocked || !d.rules.blockingEnabled) return
        val minutes = d.rules.profile(pkg).replyWindowMin
        if (minutes <= 0) return
        val now = System.currentTimeMillis()
        val label = AppLabels.label(this, pkg)
        Graph.update { x -> x.copy(ledger = Credits.freeWindow(x.ledger, pkg, minutes, now, "Reply window: $label")) }
        lastWindowAt = now
    }

    companion object {
        @Volatile var instance: MirrorService? = null
            private set
        @Volatile var lastWindowAt = 0L
    }
}
