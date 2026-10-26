package com.maven.notafraud.sense

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.maven.notafraud.Graph
import com.maven.notafraud.core.workout.WorkoutLink
import com.maven.notafraud.core.workout.WorkoutStatus
import kotlinx.serialization.json.Json

/**
 * Reads the workout app's status (docs/workout/STATE.md § 5). Not a Fraud ↔ Workout, NaF dev ↔ Workout dev.
 * Missing app or no permission = null, and every workout feature stays hidden.
 */
object WorkoutSync {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun workoutPackage(ctx: Context) = if (ctx.packageName.endsWith(".dev")) "com.maven.workout.dev" else "com.maven.workout"

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(workoutPackage(ctx), 0); true }.getOrDefault(false)

    fun read(ctx: Context): WorkoutStatus? = runCatching {
        val uri = Uri.parse("content://${workoutPackage(ctx)}.sync/status")
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val col = c.getColumnIndex("json")
            if (col < 0) null else json.decodeFromString(WorkoutStatus.serializer(), c.getString(col))
        }
    }.getOrNull()

    /** Read and store; true if anything changed. */
    fun sync(ctx: Context): Boolean {
        Graph.init(ctx)
        val s = read(ctx) ?: run {
            if (Graph.data.workout != null && !installed(ctx)) Graph.update { it.copy(workout = null) }
            return false
        }
        val before = Graph.data.workout
        Graph.update { WorkoutLink.receive(it, s, Graph.now()) }
        return before?.copy(generatedAt = 0) != s.copy(generatedAt = 0)
    }

    /** "Start workout": opens the workout app. */
    fun open(ctx: Context): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(workoutPackage(ctx)) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

class WorkoutChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        Thread {
            runCatching {
                if (WorkoutSync.sync(context)) {
                    Graph.replan()
                }
                Graph.refresh(context)
            }
            pending.finish()
        }.start()
    }
}
