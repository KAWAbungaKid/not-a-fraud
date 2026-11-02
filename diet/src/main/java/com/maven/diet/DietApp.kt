package com.maven.diet

import android.app.Application
import android.content.Context
import com.maven.diet.core.DietData
import com.maven.diet.core.DietStore
import com.maven.diet.core.Seed
import com.maven.diet.core.Targets
import com.maven.diet.link.WorkoutLink
import com.maven.diet.notify.Nagger
import com.maven.diet.sync.SyncNotifier
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class DietApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Nagger.createChannel(this)
        Nagger.schedule(this)
    }
}

/** App-wide singletons: one process, one store. */
object Graph {
    lateinit var store: DietStore
        private set
    lateinit var app: Context
        private set

    fun init(ctx: Context) {
        if (::store.isInitialized) return
        app = ctx.applicationContext
        store = DietStore(File(ctx.filesDir, "diet.json")) { Seed.data(LocalDate.now().toString()) }
        store.onChange = { SyncNotifier.changed(app, it) }
    }

    val data: DietData get() = store.current
    val zone: ZoneId get() = ZoneId.systemDefault()
    fun now() = System.currentTimeMillis()
    fun today(): LocalDate = LocalDate.now()
    fun update(fn: (DietData) -> DietData): DietData = store.update(fn)

    /** Today's targets, using Not a Troglodyte's view of whether today is a training day. */
    fun targets(date: LocalDate = today()) = Targets.forDay(data, date, if (date == today()) WorkoutLink.likelyTraining(app) else null)
}
