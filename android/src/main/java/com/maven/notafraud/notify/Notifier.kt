package com.maven.notafraud.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.maven.notafraud.R
import com.maven.notafraud.core.guard.Nag
import com.maven.notafraud.core.guard.NagAction
import com.maven.notafraud.core.plan.NowState
import com.maven.notafraud.ui.MainActivity

object Notifier {
    const val CH_NOW = "now"
    const val CH_NAG = "nag"
    const val CH_ALERT = "alert"
    const val ID_NOW = 1
    const val ID_NAG = 2
    const val ID_ALERT = 3

    @Volatile private var lastPosted: String? = null

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_NOW, "Do this now", NotificationManager.IMPORTANCE_LOW).apply {
            description = "The always-on line telling you what this block is for."
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_NAG, "Nudges", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Escalating nudges while a time-sink app is open."
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Setup problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you when the guard has been switched off or killed."
        })
    }

    fun canPost(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context, tab: String? = null, req: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            ctx, req,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { tab?.let { putExtra(MainActivity.EXTRA_TAB, it) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun postNow(ctx: Context, s: NowState, force: Boolean = false) {
        if (!canPost(ctx)) return
        val detail = listOfNotNull(s.detail.ifBlank { null }, s.routine?.let { "Routine: $it" }).joinToString("\n")
        val key = s.headline + "|" + detail + "|" + s.working
        if (!force && key == lastPosted) return
        lastPosted = key

        val b = NotificationCompat.Builder(ctx, CH_NOW)
            .setSmallIcon(R.drawable.ic_stat_naf)
            .setContentTitle(s.headline)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp(ctx))
            .setDeleteIntent(ActionReceiver.pending(ctx, ActionReceiver.REPOST))

        if (s.working) {
            b.addAction(0, "Stop", ActionReceiver.pending(ctx, ActionReceiver.STOP))
            com.maven.notafraud.Graph.data.active?.taskId?.let { b.addAction(0, "Done", ActionReceiver.pending(ctx, ActionReceiver.DONE, it)) }
        } else {
            val task = s.suggested
            if (task != null && !s.protected) {
                b.addAction(0, "Start", ActionReceiver.pending(ctx, ActionReceiver.START, task.id))
                b.addAction(0, "Done", ActionReceiver.pending(ctx, ActionReceiver.DONE, task.id))
            }
            if (s.routineId != null) b.addAction(0, "Routine done", ActionReceiver.pending(ctx, ActionReceiver.ROUTINE_DONE, s.routineId))
            else b.addAction(0, "Replan", ActionReceiver.pending(ctx, ActionReceiver.REPLAN))
        }
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_NOW, b.build()) }
    }

    fun forgetLast() { lastPosted = null }

    fun postNag(ctx: Context, nag: Nag, taskId: String?) {
        if (!canPost(ctx)) return
        val b = NotificationCompat.Builder(ctx, CH_NAG)
            .setSmallIcon(R.drawable.ic_stat_naf)
            .setContentTitle(nag.title)
            .setContentText(nag.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(nag.text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openApp(ctx, req = 7))
        when (nag.action) {
            NagAction.START_TASK -> if (taskId != null) b.addAction(0, "Start it now", ActionReceiver.pending(ctx, ActionReceiver.START, taskId))
            NagAction.WALK -> b.addAction(0, "Walk 5 min", ActionReceiver.pending(ctx, ActionReceiver.WALK))
        }
        if (nag.action == NagAction.START_TASK) b.addAction(0, "Walk instead", ActionReceiver.pending(ctx, ActionReceiver.WALK))
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_NAG, b.build()) }
    }

    fun cancelNag(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_NAG)

    fun routineNotificationId(itemId: String) = 200 + (itemId.hashCode() and 0x7fffffff) % 500

    fun postRoutine(ctx: Context, a: com.maven.notafraud.core.routine.RoutineAlert) {
        if (!canPost(ctx)) return
        val angry = a.mood == com.maven.notafraud.core.routine.Mood.ANGRY
        val b = NotificationCompat.Builder(ctx, CH_NAG)
            .setSmallIcon(R.drawable.ic_stat_naf)
            .setContentTitle(a.title)
            .setContentText(a.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (angry) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setColor(if (angry) 0xFFFF6B6B.toInt() else 0xFFFFC857.toInt())
        val twig = if (!a.fromDiet) null else ctx.packageManager.getLaunchIntentForPackage(com.maven.notafraud.sense.DietSync.dietPackage(ctx))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { PendingIntent.getActivity(ctx, 17, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        if (twig != null) {
            // Logged in Not a Twig; ticked here when Twig says so.
            b.setContentIntent(twig).addAction(0, "Open Not a Twig", twig)
        } else {
            b.setContentIntent(openApp(ctx, MainActivity.TAB_ROUTINE, req = 11))
                .addAction(0, "Done", ActionReceiver.pending(ctx, ActionReceiver.ROUTINE_DONE, a.item.id))
        }
        b.addAction(0, "15 more min", ActionReceiver.pending(ctx, ActionReceiver.ROUTINE_SNOOZE, a.item.id))
        runCatching { NotificationManagerCompat.from(ctx).notify(routineNotificationId(a.item.id), b.build()) }
    }

    fun cancelRoutine(ctx: Context, itemId: String) = NotificationManagerCompat.from(ctx).cancel(routineNotificationId(itemId))

    fun postAlert(ctx: Context, title: String, text: String, tab: String = MainActivity.TAB_SETUP) {
        if (!canPost(ctx)) return
        val b = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_naf)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, tab, req = 9))
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_ALERT, b.build()) }
    }

    fun cancelAlert(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_ALERT)

    const val ID_WINDOW = 4

    /** While a blocked app is open for free (reply window, Quick Snap, still texting): say so, and until when. */
    fun postWindow(ctx: Context, label: String, until: Long) {
        if (!canPost(ctx)) return
        val b = NotificationCompat.Builder(ctx, CH_NOW)
            .setSmallIcon(R.drawable.ic_stat_naf)
            .setContentTitle("$label is open for free")
            .setContentText("Reply window until ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(until))}. Typing keeps it open.")
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setWhen(until)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter((until - System.currentTimeMillis()).coerceAtLeast(1000) + 60_000)
        runCatching { NotificationManagerCompat.from(ctx).notify(ID_WINDOW, b.build()) }
    }

    fun cancelWindow(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(ID_WINDOW)

    /** Signs of running low: once a day, opens "How's today?". */
    fun postLow(ctx: Context, signals: List<String>) {
        if (!canPost(ctx)) return
        val b = NotificationCompat.Builder(ctx, CH_NAG)
            .setSmallIcon(R.drawable.ic_stat_naf)
            .setContentTitle("Running low? Pick how today goes")
            .setContentText(signals.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(signals.joinToString(" · ")))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, MainActivity.TAB_NOW, req = 13))
        runCatching { NotificationManagerCompat.from(ctx).notify(5, b.build()) }
    }

    /** Settings lock: changes asked for last night, waiting for a yes. */
    fun postPending(ctx: Context, labels: List<String>) {
        if (!canPost(ctx)) return
        val text = labels.joinToString(" · ")
        val b = NotificationCompat.Builder(ctx, CH_NAG)
            .setSmallIcon(R.drawable.ic_stat_naf)
            .setContentTitle("Still want these changes?")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\nOpen the app to confirm or keep things as they are."))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx, MainActivity.TAB_NOW, req = 14))
        runCatching { NotificationManagerCompat.from(ctx).notify(6, b.build()) }
    }

    /** Workout app says today's workout is needed for the weekly minimum. Tap opens the workout app. */
    fun postWorkout(ctx: Context, title: String, text: String, loud: Boolean) {
        if (!canPost(ctx)) return
        val open = ctx.packageManager.getLaunchIntentForPackage(com.maven.notafraud.sense.WorkoutSync.workoutPackage(ctx))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { PendingIntent.getActivity(ctx, 15, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val b = NotificationCompat.Builder(ctx, CH_NAG)
            .setSmallIcon(R.drawable.ic_stat_naf)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (loud) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setColor(if (loud) 0xFFFF6B6B.toInt() else 0xFFFFC857.toInt())
            .setContentIntent(open ?: openApp(ctx, MainActivity.TAB_NOW, req = 16))
        if (open != null) b.addAction(0, "Start workout", open)
        runCatching { NotificationManagerCompat.from(ctx).notify(7, b.build()) }
    }

    fun cancelWorkout(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(7)

    /** The ladder climbed: why, and the way back down. */
    fun postLadder(ctx: Context, s: com.maven.notafraud.core.guard.Ladder.State) {
        if (!canPost(ctx)) return
        val name = com.maven.notafraud.core.guard.Ladder.NAMES[s.level]
        val title = if (s.bedtime) "Bedtime: the phone is winding down" else "Level ${s.level}: $name"
        val text = (if (s.reasons.isNotEmpty()) "Why: " + s.reasons.joinToString("; ") + "." else "") +
            (if (s.wayDown.isNotBlank()) " Way down: ${s.wayDown}." else "") +
            when (s.level) { 1 -> " Credits wait longer."; 2 -> " Grayscale, and unlocking opens Now first."; 3 -> " Only your Level 3 apps open."; else -> "" }
        val b = NotificationCompat.Builder(ctx, CH_NAG)
            .setSmallIcon(R.drawable.ic_stat_naf)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(if (s.level >= 3) 0xFFFF6B6B.toInt() else 0xFFFFC857.toInt())
            .setContentIntent(openApp(ctx, MainActivity.TAB_NOW, req = 18))
        runCatching { NotificationManagerCompat.from(ctx).notify(8, b.build()) }
    }

    fun cancelLadder(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(8)
}
