package com.maven.notafraud.sense

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.maven.notafraud.Graph
import com.maven.notafraud.core.diet.DietLink
import com.maven.notafraud.core.diet.DietStatus
import kotlinx.serialization.json.Json

/**
 * Reads Not a Twig's status (docs/diet/STATE.md § 4). Not a Fraud ↔ Not a Twig, NaF dev ↔ Twig dev.
 * Missing app or no permission = null, and every diet feature stays hidden.
 */
object DietSync {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Reminders grow with time, so the routine alarm re-reads at most this often. */
    private const val MIN_GAP_MS = 4 * 60_000L
    @Volatile private var lastRead = 0L

    fun dietPackage(ctx: Context) = if (ctx.packageName.endsWith(".dev")) "com.maven.diet.dev" else "com.maven.diet"

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(dietPackage(ctx), 0); true }.getOrDefault(false)

    fun read(ctx: Context): DietStatus? = runCatching {
        val uri = Uri.parse("content://${dietPackage(ctx)}.sync/status")
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val col = c.getColumnIndex("json")
            if (col < 0) null else json.decodeFromString(DietStatus.serializer(), c.getString(col))
        }
    }.getOrNull()

    /** Read and store; true if anything changed. [force] skips the short throttle. */
    fun sync(ctx: Context, force: Boolean = true): Boolean {
        Graph.init(ctx)
        val n = Graph.now()
        if (!force && n - lastRead < MIN_GAP_MS) return false
        lastRead = n
        val s = read(ctx) ?: run {
            if (Graph.data.diet != null && !installed(ctx)) Graph.update { it.copy(diet = null) }
            return false
        }
        val before = Graph.data.diet
        Graph.update { DietLink.receive(it, s, n) }
        return before?.copy(generatedAt = 0) != s.copy(generatedAt = 0)
    }

    fun open(ctx: Context): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(dietPackage(ctx)) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

/** Not a Twig says something changed (meal logged, water, weigh-in, check-in). */
class DietChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        Thread {
            runCatching {
                DietSync.sync(context)
                Graph.refresh(context)
            }
            pending.finish()
        }.start()
    }
}
