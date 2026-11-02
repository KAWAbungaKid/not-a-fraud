package com.maven.diet.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.maven.diet.Graph
import com.maven.diet.core.Actions
import com.maven.diet.core.DayActivity
import com.maven.diet.notify.Nagger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate

/**
 * Reads Not a Troglodyte (workout app) through its sync provider: is today a training day, and the lifting
 * sessions (minutes, start time) for the diet's activity record. Release Twig reads release Troglodyte; the dev
 * build prefers the dev workout app.
 */
object WorkoutLink {
    private fun authorities(ctx: Context) =
        if (ctx.packageName.endsWith(".dev")) listOf("com.maven.workout.dev.sync", "com.maven.workout.sync")
        else listOf("com.maven.workout.sync", "com.maven.workout.dev.sync")

    private fun query(ctx: Context, path: String): String? {
        for (a in authorities(ctx)) {
            val json = runCatching {
                ctx.contentResolver.query(Uri.parse("content://$a/$path"), null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            if (json != null) return json
        }
        return null
    }

    @Volatile private var cached: Pair<Long, JsonObject?>? = null

    fun status(ctx: Context): JsonObject? {
        cached?.let { (at, v) -> if (System.currentTimeMillis() - at < 60_000) return v }
        val v = query(ctx, "status")?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        cached = System.currentTimeMillis() to v
        return v
    }

    fun connected(ctx: Context) = status(ctx) != null

    /** Trained / training today, or the week still needs workouts → plan training-day food. Null = no workout app. */
    fun likelyTraining(ctx: Context): Boolean? {
        val s = status(ctx) ?: return null
        val trained = s["trainedToday"]?.jsonPrimitive?.booleanOrNull == true
        val inProgress = s["inProgress"]?.jsonPrimitive?.booleanOrNull == true
        val done = s["doneThisWeek"]?.jsonPrimitive?.intOrNull ?: 0
        val target = s["targetPerWeek"]?.jsonPrimitive?.intOrNull ?: 7
        return trained || inProgress || done < target
    }

    /** Copy today's (and recent) lifting sessions into the diet's activity record. */
    fun pull(ctx: Context) {
        cached = null
        val since = LocalDate.now().minusDays(14)
        val arr: JsonArray = query(ctx, "sessions?since=$since")?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() } ?: return
        val byDay = arr.map { it.jsonObject }.groupBy { it["date"]?.jsonPrimitive?.content ?: "" }
        Graph.update { d ->
            byDay.entries.filter { it.key.isNotEmpty() }.fold(d) { acc, (date, list) ->
                Actions.putActivity(acc, DayActivity(
                    date = date,
                    liftMin = list.sumOf { it["minutes"]?.jsonPrimitive?.intOrNull ?: 0 },
                    liftStartAt = list.mapNotNull { it["startedAt"]?.jsonPrimitive?.longOrNull }.minOrNull(),
                    updatedAt = System.currentTimeMillis(),
                ))
            }
        }
    }
}

class WorkoutChangedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Graph.init(ctx)
        runCatching { WorkoutLink.pull(ctx) }
        runCatching { Nagger.check(ctx) }
    }
}
