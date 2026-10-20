package com.maven.notafraud.sense

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.MirroredMessage
import com.maven.notafraud.core.store.Actions
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirrors messages from apps you must stay reachable on (Snapchat) into the in-app inbox,
 * with inline reply when the app's notification offers it. That makes it safe to block the app.
 */
class MirrorService : NotificationListenerService() {

    override fun onListenerConnected() {
        Graph.init(this)
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        Graph.init(this)
        if (sbn.packageName !in Graph.data.rules.mirrored) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return

        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE) ?: "Message"
        val items: List<Pair<String, String>> = if (!messages.isNullOrEmpty()) {
            messages.mapNotNull { b ->
                val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                val sender = b.getCharSequence("sender")?.toString() ?: title.toString()
                sender to text
            }.takeLast(3)
        } else {
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
                ?: return
            listOf(title.toString() to text)
        }

        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val conversationKey = "${sbn.packageName}|$title"
        if (reply != null) replies[conversationKey] = reply

        val now = System.currentTimeMillis()
        Graph.update { d ->
            items.foldIndexed(d) { i, acc, (sender, text) ->
                Actions.addMessage(
                    acc,
                    MirroredMessage(
                        key = "${sbn.packageName}|$sender|${text.hashCode()}",
                        pkg = sbn.packageName,
                        sender = if (sender == title.toString()) sender else "$sender · $title",
                        text = text,
                        at = sbn.postTime + i,
                    ),
                )
            }
        }
        lastAt = now
    }

    companion object {
        @Volatile var instance: MirrorService? = null
            private set
        @Volatile var lastAt = 0L

        /** Conversation (pkg|title) -> the app's reply action. Lives only in memory; rebuilt as notifications arrive. */
        val replies = ConcurrentHashMap<String, Notification.Action>()

        fun conversationKey(m: MirroredMessage): String = "${m.pkg}|${m.sender.substringAfter(" · ")}"

        fun canReply(m: MirroredMessage) = replies.containsKey(conversationKey(m)) || replies.containsKey("${m.pkg}|${m.sender}")

        fun reply(ctx: Context, m: MirroredMessage, text: String): Boolean {
            val action = replies[conversationKey(m)] ?: replies["${m.pkg}|${m.sender}"] ?: return false
            val inputs = action.remoteInputs ?: return false
            val intent = Intent()
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            return runCatching { action.actionIntent.send(ctx, 0, intent); true }.getOrDefault(false)
        }
    }
}
