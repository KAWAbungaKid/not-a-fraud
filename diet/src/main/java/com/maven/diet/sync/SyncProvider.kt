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
import com.maven.diet.core.Actions
import com.maven.diet.core.DietStore
import com.maven.diet.core.MealSync
import com.maven.diet.core.Sync
import com.maven.diet.link.WorkoutLink

/**
 * Data for Not a Fraud, guarded by signature permission com.maven.permission.SYNC (read and write).
 *   query  content://com.maven.diet.sync/status → one row, column "json" = DietStatus   (debug: com.maven.diet.dev.sync)
 *   update content://com.maven.diet.sync/meal   values old / name / by / remove → change one meal slot (MealSync)
 *   update content://com.maven.diet.sync/weigh  values lb / at → today's weigh-in
 *   update content://com.maven.diet.sync/quickmeal values slot / text / at / usual → the usual for that slot, or a quick meal
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
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        val ctx = context ?: return 0
        if (values == null) return 0
        Graph.init(ctx)
        when (uri.lastPathSegment) {
            // Weigh-in typed on Not a Fraud's check-in.
            "weigh" -> {
                val lb = values.getAsDouble("lb") ?: return 0
                val at = values.getAsLong("at") ?: Graph.now()
                val date = java.time.Instant.ofEpochMilli(at).atZone(Graph.zone).toLocalDate()
                val before = Graph.data.weights.size to Graph.data.weights.lastOrNull()
                Graph.update { Actions.setWeight(it, date, lb, at) }
                return if ((Graph.data.weights.size to Graph.data.weights.lastOrNull()) != before) 1 else 0
            }
            // "What did you eat?" from the check-in: the usual for that slot, or a quick note filled in later.
            "quickmeal" -> {
                val slot = values.getAsString("slot") ?: return 0
                val at = values.getAsLong("at") ?: Graph.now()
                val date = java.time.Instant.ofEpochMilli(at).atZone(Graph.zone).toLocalDate()
                val text = values.getAsString("text").orEmpty()
                val usual = values.getAsBoolean("usual") == true
                val before = Graph.data
                Graph.update { d ->
                    val x = if (usual) com.maven.diet.core.Quick.logUsual(d, slot, date, at) else d
                    if (x === d) com.maven.diet.core.Quick.add(d, slot, text.ifBlank { "the usual" }, date, at) else x
                }
                return if (Graph.data != before) 1 else 0
            }
        }
        if (uri.lastPathSegment != "meal") return 0
        val before = Graph.data.settings
        Graph.update { d ->
            Actions.settings(d) {
                MealSync.apply(it, values.getAsString("old"), values.getAsString("name"), values.getAsString("by"), values.getAsBoolean("remove") == true)
            }
        }
        return if (Graph.data.settings != before) 1 else 0
    }
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
