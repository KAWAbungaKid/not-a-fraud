package com.maven.notafraud

import android.app.Application
import android.content.Context
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.plan.NowState
import com.maven.notafraud.core.plan.Planning
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.seed.Seed
import com.maven.notafraud.core.store.DataStore
import com.maven.notafraud.notify.Alarms
import com.maven.notafraud.notify.Notifier
import com.maven.notafraud.work.Workers
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class NafApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifier.createChannels(this)
        Workers.schedule(this)
        Graph.refresh(this)
    }
}

/** App-wide singletons. One process, one store. */
object Graph {
    lateinit var store: DataStore
        private set

    fun init(ctx: Context) {
        if (::store.isInitialized) return
        store = DataStore(File(ctx.filesDir, "data.json")) { Seed.data(System.currentTimeMillis()) }
    }

    val data: AppData get() = store.current
    val zone: ZoneId get() = Time.zone(data.settings.zoneId)
    fun now() = System.currentTimeMillis()
    fun today(): LocalDate = Time.dateOf(now(), zone)
    fun startOfToday(): Long = Time.at(today(), 0, zone)

    fun update(fn: (AppData) -> AppData): AppData = store.update(fn)

    /** Ensures today's plan exists and returns what to do right now. */
    fun nowState(): NowState {
        val n = now()
        val d = store.update { Planning.ensureToday(it, n) }
        return com.maven.notafraud.core.plan.NowResolver.resolve(d.todayPlan!!, d, n)
    }

    /** Set when the burnout nudge fired; the Now tab opens "How's today?" once. */
    @Volatile var lowPromptPending = false

    fun replan() {
        val n = now()
        store.update { Planning.replan(it, n) }
    }

    /** Re-post the notification and re-arm the next block-boundary alarm. Cheap; call after any change. */
    fun refresh(ctx: Context) {
        // A block slipped by: re-plan the rest of the day (drops what no longer fits).
        val n = now()
        store.update { Planning.autoReplan(Planning.ensureToday(it, n), n) }
        // Two or more signs of running low: one nudge a day toward "How's today?".
        if (com.maven.notafraud.core.stats.Burnout.shouldPrompt(data, n)) {
            Notifier.postLow(ctx, com.maven.notafraud.core.stats.Burnout.signals(data, n))
            store.update { com.maven.notafraud.core.store.Actions.markPrompted(it, today(), n) }
            lowPromptPending = true
        }
        // Settings lock: last night's changes are ready to confirm.
        val ready = com.maven.notafraud.core.store.Lock.due(data, n).filter { !it.notified }
        if (ready.isNotEmpty()) {
            Notifier.postPending(ctx, ready.map { it.label })
            store.update { com.maven.notafraud.core.store.Lock.markNotified(it, ready.map { c -> c.id }.toSet()) }
        }
        Notifier.postNow(ctx, nowState())
        com.maven.notafraud.notify.RoutineAlerts.check(ctx)
        Alarms.scheduleNext(ctx)
        com.maven.notafraud.sense.CalendarWriter.syncAsync(ctx)
    }

    /** Apply a change that affects the plan, then re-plan and refresh. */
    fun changeAndReplan(ctx: Context, fn: (AppData) -> AppData) {
        update(fn)
        replan()
        refresh(ctx)
    }
}
