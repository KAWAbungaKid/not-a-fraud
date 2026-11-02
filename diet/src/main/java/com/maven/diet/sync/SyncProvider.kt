package com.maven.diet.sync

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.maven.diet.Graph
import com.maven.diet.core.DietData
import com.maven.diet.core.DietStatus
import com.maven.diet.core.DietStore
import com.maven.diet.core.Sync
import com.maven.diet.link.WorkoutLink

/**
 * Read-only data for Not a Fraud, guarded by signature permission com.maven.permission.SYNC.
 *   content://com.maven.diet.sync/status → one row, column "json" = DietStatus   (debug: com.maven.diet.dev.sync)
 */
class SyncProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val ctx = context ?: return null
        Graph.init(ctx)
        if (uri.lastPathSegment != "status") return null
        val st = Sync.status(Graph.data, Graph.now(), Graph.zone, WorkoutLink.likelyTraining(ctx))
        return MatrixCursor(arrayOf("json")).apply { addRow(arrayOf(DietStore.JSON.encodeToString(DietStatus.serializer(), st))) }
    }

    override fun getType(uri: Uri): String = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0
}

/** Broadcast com.maven.diet.CHANGED to Not a Fraud when something it shows changed (meal logged, water, weigh-in…). */
object SyncNotifier {
    const val ACTION = "com.maven.diet.CHANGED"
    private val TARGETS = listOf("com.maven.notafraud", "com.maven.notafraud.dev")
    @Volatile private var lastKey: String? = null

    fun changed(ctx: Context, d: DietData) {
        val key = listOf(d.log.size, d.water.size, d.weights.size, d.checkins.size, d.settings.hashCode()).joinToString("|")
        if (key == lastKey) return
        lastKey = key
        for (pkg in TARGETS) runCatching { ctx.sendBroadcast(Intent(ACTION).setPackage(pkg), "com.maven.permission.SYNC") }
    }
}
