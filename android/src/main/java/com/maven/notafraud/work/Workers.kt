package com.maven.notafraud.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.maven.notafraud.Graph
import com.maven.notafraud.core.plan.Generated
import com.maven.notafraud.core.plan.Planning
import com.maven.notafraud.core.plan.RoughDay
import com.maven.notafraud.core.store.Actions
import com.maven.notafraud.guard.GuardService
import com.maven.notafraud.notify.Notifier
import com.maven.notafraud.sense.Health
import com.maven.notafraud.sense.Perms
import com.maven.notafraud.sense.PhoneCalendar
import com.maven.notafraud.sense.UsageReader
import java.util.concurrent.TimeUnit

object Workers {
    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<HourlyWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("hourly", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Pulls sensors into the store. Shared by the worker and app open. */
    suspend fun syncSensors(ctx: Context) {
        Graph.init(ctx)
        val snapshot = runCatching { UsageReader.snapshot(ctx) }.getOrNull()
        if (snapshot != null && snapshot.minutesByApp.isNotEmpty()) Graph.update { Actions.recordUsage(it, snapshot) }

        PhoneCalendar.readSplit(ctx)?.let { split ->
            val before = Graph.data.let { d -> d.events.filter { it.source == PhoneCalendar.SOURCE }.toSet() to d.tasks.filter { it.sourceKey?.startsWith("deadline:") == true || it.sourceKey?.startsWith("test:") == true || it.sourceKey?.startsWith("homework:") == true }.toSet() }
            val n = Graph.now()
            Graph.update { d ->
                val withEvents = Actions.replaceEvents(d, PhoneCalendar.SOURCE, split.events)
                Generated.autoExams(com.maven.notafraud.core.plan.Homework.sync(Generated.syncTests(Generated.syncDeadlines(withEvents, split.deadlines, n), split.tests, n), split.homework, n), n)
            }
            val after = Graph.data.let { d -> d.events.filter { it.source == PhoneCalendar.SOURCE }.toSet() to d.tasks.filter { it.sourceKey?.startsWith("deadline:") == true || it.sourceKey?.startsWith("test:") == true || it.sourceKey?.startsWith("homework:") == true }.toSet() }
            // Something moved in Google Calendar or Canvas: re-plan around it.
            if (after != before) Graph.replan()
        }
        val wasRough = Graph.data.todayPlan?.roughDay == true
        val health = runCatching { Health.read(ctx) }.getOrNull()
        if (health != null) {
            Graph.update { d -> health.days.fold(d) { x, dc -> Actions.autoFromHealth(Actions.upsertDay(x, dc), java.time.LocalDate.parse(dc.date)) } }
            val todayCtx = Graph.data.days.firstOrNull { it.date == Graph.today().toString() }
            val nowRough = RoughDay.isRough(todayCtx, Graph.data.settings.badSleepHours)
            // Bad sleep came in after the plan was made: soften the rest of the day.
            if (nowRough != wasRough) Graph.replan()
        }
        // Workout app: done / started / at-risk changes re-plan the day.
        if (runCatching { com.maven.notafraud.sense.WorkoutSync.sync(ctx) }.getOrDefault(false)) Graph.replan()
        // Not a Twig: meals logged tick the routine; its reminders join the routine nags.
        runCatching { com.maven.notafraud.sense.DietSync.sync(ctx) }
        Graph.update { Planning.ensureToday(it, Graph.now()) }
        Graph.refresh(ctx)
    }
}

/**
 * Hourly, battery-not-low: watchdog for the guard, usage rollup, calendar and health sync.
 * One UI's battery manager is the most likely reason the app silently stops — this catches it.
 */
class HourlyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Graph.init(ctx)
        // With the computer step done, the guard switches itself back on instead of asking.
        if (!Perms.accessibility(ctx) && com.maven.notafraud.guard.SecureSettings.reEnableGuard(ctx)) {
            com.maven.notafraud.guard.GuardLog.init(ctx)
            com.maven.notafraud.guard.GuardLog.add("Guard", "was off: switched back on")
        } else if (!Perms.accessibility(ctx)) {
            Notifier.postAlert(ctx, "Blocking is off", "The Not a Fraud guard isn't enabled. Tap to turn it back on.")
        } else if (GuardService.instance == null && run { kotlinx.coroutines.delay(8_000); GuardService.instance == null }) {
            Notifier.postAlert(
                ctx, "Guard was stopped",
                "Android stopped the guard. Exempt Not a Fraud from battery optimization and Sleeping apps (Setup tab).",
            )
        }
        runCatching { Workers.syncSensors(ctx) }
        return Result.success()
    }
}
