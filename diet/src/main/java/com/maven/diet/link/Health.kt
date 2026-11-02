package com.maven.diet.link

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.maven.diet.Graph
import com.maven.diet.core.Actions
import com.maven.diet.core.DayActivity
import java.time.LocalDate

/** Active calories + steps per day from Health Connect (Samsung Health writes them from the watch). */
object Health {
    val ACTIVE = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    val STEPS = HealthPermission.getReadPermission(StepsRecord::class)
    val PERMISSIONS = setOf(ACTIVE, STEPS)

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(ctx: Context): Set<String> =
        if (!available(ctx)) emptySet()
        else runCatching { HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    @Volatile var lastResult: String = "Not read yet"

    /** Last 15 days, one aggregate per day. Cheap enough to run on every app open. */
    suspend fun pull(ctx: Context) {
        val g = granted(ctx)
        if (ACTIVE !in g && STEPS !in g) { lastResult = "No permission"; return }
        val c = HealthConnectClient.getOrCreate(ctx)
        val zone = Graph.zone
        val today = LocalDate.now()
        var n = 0
        for (i in 0..14) {
            val day = today.minusDays(i.toLong())
            val start = day.atStartOfDay(zone).toInstant()
            val end = if (i == 0) java.time.Instant.now() else day.plusDays(1).atStartOfDay(zone).toInstant()
            val r = runCatching {
                c.aggregate(AggregateRequest(
                    metrics = buildSet {
                        if (ACTIVE in g) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                        if (STEPS in g) add(StepsRecord.COUNT_TOTAL)
                    },
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                ))
            }.getOrNull() ?: continue
            val kcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
            val steps = r[StepsRecord.COUNT_TOTAL]
            if (kcal == null && steps == null) continue
            n++
            Graph.update { Actions.putActivity(it, DayActivity(day.toString(), activeKcal = kcal, steps = steps, updatedAt = System.currentTimeMillis())) }
        }
        lastResult = "$n of 15 days had data"
    }
}
