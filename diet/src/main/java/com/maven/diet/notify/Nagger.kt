package com.maven.diet.notify

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
import com.maven.diet.Graph
import com.maven.diet.R
import com.maven.diet.core.Actions
import com.maven.diet.core.NagKind
import com.maven.diet.core.Nags
import com.maven.diet.link.WorkoutLink
import com.maven.diet.ui.MainActivity

/**
 * Every 30 min while awake: post the loudest overdue thing (meal, water, weigh-in, check-in).
 * Level 3 goes on the high-importance channel so it makes noise. Water nags get a "+16 oz" button.
 */
object Nagger {
    private const val QUIET = "nags"
    private const val LOUD = "nags-loud"
    private const val ID = 11
    const val ACTION_TICK = "com.maven.diet.TICK"
    const val ACTION_WATER = "com.maven.diet.WATER16"

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(QUIET, "Food & water reminders", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(LOUD, "Food & water — overdue", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun pi(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, NagReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 30 * 60_000L, pi(ctx, ACTION_TICK, 1))
    }

    fun check(ctx: Context) {
        Graph.init(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val nags = Nags.due(Graph.data, Graph.now(), Graph.zone, WorkoutLink.likelyTraining(ctx) ?: true)
        if (nags.isEmpty()) { nm.cancel(ID); return }
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val top = nags.maxBy { it.level }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val more = if (nags.size > 1) "\n+ " + nags.filter { it !== top }.joinToString(" · ") { it.title } else ""
        val b = NotificationCompat.Builder(ctx, if (top.level >= 3) LOUD else QUIET)
            .setSmallIcon(R.drawable.ic_stat_diet)
            .setContentTitle(top.title + if (top.level >= 2) " (${"!".repeat(top.level - 1)})" else "")
            .setContentText(top.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(top.text + more))
            .setContentIntent(open)
            .setOnlyAlertOnce(top.level < 3)
        if (nags.any { it.kind == NagKind.WATER }) b.addAction(0, "+16 oz water", pi(ctx, ACTION_WATER, 2))
        nm.notify(ID, b.build())
    }
}

class NagReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Graph.init(ctx)
        if (intent.action == Nagger.ACTION_WATER) Graph.update { Actions.addWater(it, Graph.today(), 16.0, Graph.now()) }
        runCatching { Nagger.check(ctx) }
        if (intent.action != Nagger.ACTION_WATER) Nagger.schedule(ctx)
    }
}
