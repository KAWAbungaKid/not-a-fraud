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
            ROUTINE_DONE -> if (id != null) {
                Graph.update { Actions.checkRoutine(it, id, now) }
                Notifier.cancelRoutine(ctx, id)
            }
            ROUTINE_SNOOZE -> if (id != null) {
                // Next angry nag in ~15 min instead of 30.
                Graph.update { Actions.markAlerted(it, listOf(id), now - 15 * Time.MIN) }
                Notifier.cancelRoutine(ctx, id)
            }
            // Routine alarm: Twig's reminders grow with time, so look at it again before checking.
            TICK -> runCatching { com.maven.notafraud.sense.DietSync.sync(ctx, force = false) }
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
        const val ROUTINE_DONE = "naf.ROUTINE_DONE"
        const val ROUTINE_SNOOZE = "naf.ROUTINE_SNOOZE"
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
        val tomorrow = Time.at(Graph.today().plusDays(1), 5 * 60, Graph.zone)
        val next = listOfNotNull(
            plan?.boundaries?.firstOrNull { it > now + 30_000 },
            com.maven.notafraud.core.routine.RoutinePolicy.nextAlertAt(Graph.data, now),
            tomorrow,
        ).min()
        val pi = ActionReceiver.pending(ctx, ActionReceiver.TICK)
        runCatching {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }
}


/** Turns due routine alerts into notifications, once each (see RoutinePolicy.alertsDue). */
object RoutineAlerts {
    fun check(ctx: Context, bedtime: Boolean = false) {
        val now = Graph.now()
        val alerts = com.maven.notafraud.core.routine.RoutinePolicy.alertsDue(Graph.data, now, bedtime)
        if (alerts.isEmpty()) return
        alerts.forEach { Notifier.postRoutine(ctx, it) }
        val loud = alerts.any { it.item.trigger == com.maven.notafraud.core.model.RoutineTrigger.BEDTIME || it.item.category == com.maven.notafraud.core.model.RoutineCategory.SLEEP }
        if (loud && Graph.data.settings.bedtimeSound) Sounds.bedtime(ctx)
        Graph.update { Actions.markAlerted(it, alerts.map { a -> a.item.id }, now) }
    }
}
