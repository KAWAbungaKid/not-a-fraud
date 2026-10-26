package com.maven.workout.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.maven.workout.Graph
import com.maven.workout.R
import com.maven.workout.core.Risk
import com.maven.workout.core.Suggest
import com.maven.workout.core.Week
import com.maven.workout.ui.MainActivity
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One check a day at the reminder hour: if the week's minimum is getting tight and you haven't trained
 * today, say so. (Not a Fraud does the real nagging; this is the workout app's own floor.)
 */
object Reminder {
    private const val CHANNEL = "minimum"
    private const val ID = 7

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Weekly minimum", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun schedule(ctx: Context) {
        Graph.init(ctx)
        val s = Graph.data.settings
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, ReminderReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.cancel(pi)
        if (!s.reminder) return
        val now = LocalDateTime.now()
        var at = now.toLocalDate().atTime(s.reminderHour.coerceIn(0, 23), 0)
        if (!at.isAfter(now)) at = at.plusDays(1)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pi)
    }

    fun check(ctx: Context) {
        Graph.init(ctx)
        Graph.roll()
        val d = Graph.data
        val w = Week.status(d, LocalDate.now())
        if (w.trainedToday || d.active != null) return
        if (w.risk != Risk.TIGHT && w.risk != Risk.MUST_TODAY) return
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pick = Suggest.today(d, LocalDate.now()).pick?.template?.name
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentTitle(if (w.risk == Risk.MUST_TODAY) "No spare days left this week" else "Weekly minimum: cutting it close")
            .setContentText(w.message() + (pick?.let { " Today: $it." } ?: ""))
            .setStyle(NotificationCompat.BigTextStyle().bigText(w.message() + (pick?.let { " Today: $it." } ?: "")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID, n)
    }

    const val ACTION = "com.maven.workout.REMINDER"
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Reminder.ACTION) runCatching { Reminder.check(ctx) }
        Reminder.schedule(ctx)
    }
}
