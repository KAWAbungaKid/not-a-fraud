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

    /**
     * Weekly workout minimum (from the workout app). MUST_TODAY: from settings.workoutNagFromMin, a nudge every
     * hour that gets louder after 6pm, until it's started or done. MISSED: one honest note per week, then quiet.
     */
    private fun workoutNag(ctx: Context, n: Long) {
        val d = data
        val s = com.maven.notafraud.core.workout.WorkoutLink.today(d, n) ?: return
        val prefs = ctx.getSharedPreferences("workout_nag", Context.MODE_PRIVATE)
        val minute = Time.minuteOfDay(n, zone)
        when {
            com.maven.notafraud.core.workout.WorkoutLink.mustToday(d, n) && minute >= d.settings.workoutNagFromMin -> {
                if (n - prefs.getLong("last", 0) < 60 * Time.MIN) return
                prefs.edit().putLong("last", n).apply()
                val late = minute >= 18 * 60
                Notifier.postWorkout(
                    ctx,
                    if (late) "Workout. Today. It's the only way the week holds." else "Today's workout keeps your minimum",
                    s.message + (s.suggestedToday?.let { " Suggested: $it." } ?: "") +
                        (if (d.settings.workoutPausesCredits) " Credits are paused until it's started." else "") + " Short mode counts.",
                    loud = late,
                )
            }
            s.risk == com.maven.notafraud.core.workout.WorkoutLink.MISSED && prefs.getString("missed", null) != s.weekStart -> {
                prefs.edit().putString("missed", s.weekStart).apply()
                Notifier.postWorkout(ctx, "This week's minimum is gone", s.message + " No more nagging this week. Next week starts clean.", loud = false)
            }
            s.trainedToday || s.inProgress -> Notifier.cancelWorkout(ctx)
        }
    }

    /**
     * The brick ladder's side effects: grayscale (with the computer step), a note when it climbs, the guard
     * re-checking the app in front, the home widget and (if switched on) the lock screen wallpaper.
     */
    private fun ladder(ctx: Context, n: Long, st: NowState) {
        val d = data
        val lad = com.maven.notafraud.core.guard.Ladder.state(d, n)
        val l = d.settings.ladder
        com.maven.notafraud.guard.SecureSettings.setGrayscale(ctx, l.enabled && lad.level >= l.grayscaleFrom)
        val prefs = ctx.getSharedPreferences("ladder", Context.MODE_PRIVATE)
        val before = prefs.getInt("level", 0)
        val beforeBed = prefs.getBoolean("bed", false)
        if (lad.level != before || lad.bedtime != beforeBed) {
            prefs.edit().putInt("level", lad.level).putBoolean("bed", lad.bedtime).apply()
            if (lad.level > before) Notifier.postLadder(ctx, lad)
            else if (lad.level == 0) Notifier.cancelLadder(ctx)
            com.maven.notafraud.guard.GuardService.instance?.let { g -> android.os.Handler(android.os.Looper.getMainLooper()).post { g.reevaluate() } }
        }
        runCatching { com.maven.notafraud.ui.NowWidget.push(ctx) }
        if (l.lockWallpaper) {
            val picks = d.picks[today().toString()].orEmpty().mapNotNull { id -> d.tasks.firstOrNull { it.id == id }?.title }
            Thread { runCatching { com.maven.notafraud.notify.LockWallpaper.update(ctx, st, lad, picks) } }.start()
        }
    }

    /** Open the Tasks page on its To-do tab once. */
    @Volatile var openTodo = false

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
        workoutNag(ctx, n)
        val st = nowState()
        Notifier.postNow(ctx, st)
        ladder(ctx, n, st)
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
