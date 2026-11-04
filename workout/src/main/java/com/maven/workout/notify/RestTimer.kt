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
import com.maven.workout.R
import com.maven.workout.ui.MainActivity

/**
 * The rest timer outside the app (screen off, phone in a pocket): a countdown in the notification shade, and a
 * buzz + "Rest's over" when it ends. Cancelled when the next set is ticked or the rest is skipped.
 */
object RestTimer {
    private const val CHANNEL = "rest"
    private const val CHANNEL_DONE = "rest-done"
    private const val ID = 31

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Rest timer", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Rest over", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 250, 120, 250)
        })
    }

    private fun canPost(ctx: Context) = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 30, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    private fun alarm(ctx: Context) = PendingIntent.getBroadcast(ctx, 32, Intent(ctx, RestDoneReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun start(ctx: Context, endsAt: Long, label: String) {
        channels(ctx)
        if (canPost(ctx)) {
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_workout)
                .setContentTitle("Rest")
                .setContentText(label)
                .setWhen(endsAt).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setContentIntent(open(ctx))
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(ID, n)
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        runCatching {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, alarm(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, alarm(ctx))
        }
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarm(ctx))
        ctx.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    fun done(ctx: Context) {
        channels(ctx)
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentTitle("Rest's over")
            .setContentText("Next set.")
            .setAutoCancel(true).setTimeoutAfter(60_000)
            .setContentIntent(open(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID, n)
    }
}

class RestDoneReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) = RestTimer.done(ctx)
}
