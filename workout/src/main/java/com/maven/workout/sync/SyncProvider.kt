package com.maven.workout.sync

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.maven.workout.Graph
import com.maven.workout.core.Sync
import com.maven.workout.core.SyncSession
import com.maven.workout.core.SyncStatus
import com.maven.workout.core.WorkoutData
import com.maven.workout.core.WorkoutStore
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate

/**
 * Read-only data for Not a Fraud. Guarded by the signature permission com.maven.permission.SYNC.
 *
 *   content://com.maven.workout.sync/status              → one row, column "json" = SyncStatus
 *   content://com.maven.workout.sync/sessions?since=DATE → one row, column "json" = [SyncSession]
 *
 * (Debug build: com.maven.workout.dev.sync.)
 */
class SyncProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val ctx = context ?: return null
        Graph.init(ctx)
        Graph.roll()
        val d = Graph.data
        val json = when (uri.lastPathSegment) {
            "status" -> WorkoutStore.JSON.encodeToString(SyncStatus.serializer(), Sync.status(d, Graph.today(), Graph.now()))
            "sessions" -> {
                val since = uri.getQueryParameter("since")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                WorkoutStore.JSON.encodeToString(ListSerializer(SyncSession.serializer()), Sync.sessions(d, since))
            }
            else -> return null
        }
        return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(json)) }
    }

    override fun getType(uri: Uri): String = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    /** update content://…sync/hint  date=YYYY-MM-DD sport=Climbing|null: Not a Fraud's "sport tomorrow". */
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        val ctx = context ?: return 0
        if (uri.lastPathSegment != "hint" || values == null) return 0
        val date = values.getAsString("date") ?: return 0
        Graph.init(ctx)
        val before = Graph.data.sportDays[date]
        val sport = values.getAsString("sport")
        if (before == sport) return 0
        Graph.update { com.maven.workout.core.Actions.setSportDay(it, date, sport) }
        return 1
    }
}

/**
 * Tells Not a Fraud something it cares about changed (a workout finished/started, the week's status moved),
 * so it can re-read /status without polling. Explicit broadcast per package, signature-protected.
 */
object SyncNotifier {
    const val ACTION = "com.maven.workout.CHANGED"
    private val TARGETS = listOf("com.maven.notafraud", "com.maven.notafraud.dev", "com.maven.diet", "com.maven.diet.dev")
    @Volatile private var lastKey: String? = null

    fun changed(ctx: Context, d: WorkoutData) {
        val st = runCatching { Sync.status(d, LocalDate.now(), System.currentTimeMillis()) }.getOrNull() ?: return
        val key = listOf(st.doneThisWeek, st.risk, st.inProgress, st.suggestedToday, st.minPerWeek, st.targetPerWeek, st.lastWorkoutDate).joinToString("|")
        if (key == lastKey) return
        lastKey = key
        for (pkg in TARGETS) runCatching {
            ctx.sendBroadcast(Intent(ACTION).setPackage(pkg), "com.maven.permission.SYNC")
        }
    }
}
