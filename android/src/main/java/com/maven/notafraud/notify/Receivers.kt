package com.maven.notafraud.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.maven.notafraud.Graph
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.store.Actions
import com.maven.notafraud.work.Workers

/** Buttons on the notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Graph.init(ctx)
        val id = intent.getStringExtra(EXTRA_ID)
        val now = Graph.now()
        when (intent.action) {
            START -> Graph.update { Actions.startSession(it, id, null, now) }
            STOP -> Graph.update { Actions.stopSession(it, now) }
            DONE -> if (id != null) Graph.changeAndReplan(ctx) { Actions.setDone(it, id, true, now) }
            WALK -> Graph.update { Actions.startSession(it, null, "Walk", now) }
            REPLAN -> Graph.replan()
            REPOST -> Notifier.forgetLast()
            TICK -> Unit
        }
        if (intent.action == START || intent.action == WALK) Notifier.cancelNag(ctx)
        Graph.refresh(ctx)
    }

    companion object {
        const val START = "naf.START"
        const val STOP = "naf.STOP"
        const val DONE = "naf.DONE"
        const val WALK = "naf.WALK"
        const val REPLAN = "naf.REPLAN"
        const val REPOST = "naf.REPOST"
        const val TICK = "naf.TICK"
        const val EXTRA_ID = "id"

        fun pending(ctx: Context, action: String, id: String? = null): PendingIntent {
            val i = Intent(ctx, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id)
            return PendingIntent.getBroadcast(
                ctx, (action + id).hashCode(), i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Graph.init(ctx)
        Workers.schedule(ctx)
        Notifier.forgetLast()
        Graph.refresh(ctx)
    }
}

/**
 * One alarm at a time: the next block boundary (or tomorrow's day start). Each firing
 * updates the notification and arms the next one. About 10-15 alarms a day; no polling.
 */
object Alarms {
    fun scheduleNext(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = Graph.now()
        val plan = Graph.data.todayPlan
        val next = plan?.boundaries?.firstOrNull { it > now + 30_000 }
            ?: Time.at(Graph.today().plusDays(1), Graph.data.settings.dayStartMin - 30, Graph.zone)
        val pi = ActionReceiver.pending(ctx, ActionReceiver.TICK)
        runCatching {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }
}
