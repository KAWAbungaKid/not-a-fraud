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

    /** A new message notification from a blocked app: opening it soon after gets a reply window. */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        Graph.init(this)
        val d = Graph.data
        if (sbn.packageName !in d.rules.blocked) return
        val n = sbn.notification ?: return
        if (sbn.isOngoing || n.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return
        // Only people writing to you, not "X posted a story" or streak reminders, when the app labels its notifications.
        val cat = n.category
        if (cat != null && cat != android.app.Notification.CATEGORY_MESSAGE && cat != android.app.Notification.CATEGORY_CALL && cat != android.app.Notification.CATEGORY_SOCIAL) return
        if (n.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0 && sbn.postTime < (d.ledger.lastMessageAt[sbn.packageName] ?: 0)) return
        Graph.update { x -> x.copy(ledger = Credits.messageArrived(x.ledger, sbn.packageName, sbn.postTime)) }
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
        Graph.update { x ->
            x.copy(ledger = Credits.freeWindow(x.ledger, pkg, minutes, now, "Reply window: $label").let { l ->
                l.copy(messageWindowUsed = l.messageWindowUsed + (pkg to (l.lastMessageAt[pkg] ?: now)))
            })
        }
        lastWindowAt = now
        // The block screen may have beaten us to it: close it and open the app.
        com.maven.notafraud.guard.BlockActivity.current?.takeIf { it.subject == pkg }?.openNow()
    }

    companion object {
        @Volatile var instance: MirrorService? = null
            private set
        @Volatile var lastWindowAt = 0L
    }
}
