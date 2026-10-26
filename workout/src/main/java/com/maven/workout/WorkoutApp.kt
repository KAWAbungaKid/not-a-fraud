package com.maven.workout

import android.app.Application
import android.content.Context
import com.maven.workout.core.Cycle
import com.maven.workout.core.Seed
import com.maven.workout.core.WorkoutData
import com.maven.workout.core.WorkoutStore
import com.maven.workout.notify.Reminder
import com.maven.workout.sync.SyncNotifier
import java.io.File
import java.time.LocalDate

class WorkoutApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Reminder.createChannel(this)
        Reminder.schedule(this)
    }
}

/** App-wide singletons: one process, one store. */
object Graph {
    lateinit var store: WorkoutStore
        private set
    lateinit var app: Context
        private set

    /** Notes from the last cycle roll ("repeating week 3"), shown once on Today. */
    @Volatile var cycleNotes: List<String> = emptyList()

    /** Running stopwatches for timed sets, keyed "sessionId/entry/set" → start millis. Process lifetime only. */
    val stopwatches = HashMap<String, Long>()

    fun init(ctx: Context) {
        if (::store.isInitialized) return
        app = ctx.applicationContext
        store = WorkoutStore(File(ctx.filesDir, "workout.json")) { Seed.data(LocalDate.now().toString()) }
        store.onChange = { SyncNotifier.changed(app, it) }
        roll()
    }

    val data: WorkoutData get() = store.current
    fun now() = System.currentTimeMillis()
    fun today(): LocalDate = LocalDate.now()

    fun update(fn: (WorkoutData) -> WorkoutData): WorkoutData = store.update(fn)

    /** Advance the program week/block for any calendar weeks that finished. Cheap; call on open. */
    fun roll() {
        var notes: List<String> = emptyList()
        store.update { d -> Cycle.roll(d, today()).also { notes = it.notes }.data }
        if (notes.isNotEmpty()) cycleNotes = notes
    }
}
