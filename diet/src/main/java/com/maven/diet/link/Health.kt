package com.maven.diet.link

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
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
    val SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val PERMISSIONS = setOf(ACTIVE, STEPS, SLEEP)

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
        if (SLEEP in g) runCatching { pullSleep(c, zone, today) }
    }

    /**
     * Night sleep per day = sleep sessions that ended between 3 am and 2 pm that day (naps after that don't count),
     * minus awake stages when the watch recorded them. Used by the bad-day suggestions, insights and the weekly review.
     */
    private suspend fun pullSleep(c: HealthConnectClient, zone: java.time.ZoneId, today: LocalDate) {
        val from = today.minusDays(15).atStartOfDay(zone).toInstant()
        val sessions = c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, java.time.Instant.now()))).records
        val byDay = HashMap<LocalDate, Double>()
        for (s in sessions) {
            val end = s.endTime.atZone(zone)
            if (end.hour !in 3..13) continue
            val awake = s.stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE }.sumOf { java.time.Duration.between(it.startTime, it.endTime).toMinutes() }
            val h = (java.time.Duration.between(s.startTime, s.endTime).toMinutes() - awake) / 60.0
            byDay[end.toLocalDate()] = (byDay[end.toLocalDate()] ?: 0.0) + h
        }
        for ((day, h) in byDay) Graph.update { Actions.putActivity(it, DayActivity(day.toString(), sleepH = Math.round(h * 10) / 10.0, updatedAt = System.currentTimeMillis())) }
    }
}
