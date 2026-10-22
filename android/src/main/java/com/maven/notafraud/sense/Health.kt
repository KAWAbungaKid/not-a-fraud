package com.maven.notafraud.sense

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.DayContext
import com.maven.notafraud.core.plan.Time
import java.time.Duration
import java.time.Instant

/**
 * Health Connect: last night's sleep, today's workouts and steps. Samsung Health writes into it.
 * Read on app open and from the hourly worker until today's sleep is in.
 */
object Health {
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
    )

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    private fun client(ctx: Context) = HealthConnectClient.getOrCreate(ctx)

    suspend fun granted(ctx: Context): Boolean =
        available(ctx) && runCatching { client(ctx).permissionController.getGrantedPermissions().containsAll(PERMISSIONS) }.getOrDefault(false)

    data class Readout(val ctx: DayContext, val summary: String)

    suspend fun read(ctx: Context): Readout? {
        if (!granted(ctx)) return null
        val c = client(ctx)
        val zone = Graph.zone
        val today = Graph.today()
        val startOfToday = Instant.ofEpochMilli(Time.at(today, 0, zone))
        val now = Instant.ofEpochMilli(Graph.now())

        // Night sleep = the longest session ending between 3am and 2pm today (started the evening before or overnight).
        // Naps = other sessions today of 10 min to 4 h. v0.2 summed everything, so a nap padded "last night".
        val window = TimeRangeFilter.between(startOfToday.minus(Duration.ofHours(8)), now)
        val sessions = runCatching { c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, window)).records }.getOrDefault(emptyList())
        val nightCandidates = sessions.filter {
            it.endTime.isAfter(startOfToday.plus(Duration.ofHours(3))) && it.endTime.isBefore(startOfToday.plus(Duration.ofHours(14))) &&
                Duration.between(it.startTime, it.endTime).toMinutes() >= 120
        }
        val night = nightCandidates.maxByOrNull { Duration.between(it.startTime, it.endTime).toMillis() }
        val naps = sessions.filter { it !== night && it.startTime.isAfter(startOfToday.plus(Duration.ofHours(6))) }
            .filter { Duration.between(it.startTime, it.endTime).toMinutes() in 10..240 }
            .map { com.maven.notafraud.core.model.Nap(it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
        val sleepHours = night?.let { Duration.between(it.startTime, it.endTime).toMillis() / 3_600_000.0 }
        val sleeps = listOfNotNull(night)

        val dayFilter = TimeRangeFilter.between(startOfToday, now)
        val workouts = runCatching { c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, dayFilter)).records }.getOrDefault(emptyList())
        val workoutMin = workouts.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt()

        val steps = runCatching {
            c.aggregate(AggregateRequest(metrics = setOf(StepsRecord.COUNT_TOTAL), timeRangeFilter = dayFilter))[StepsRecord.COUNT_TOTAL]
        }.getOrNull()

        val dc = DayContext(
            date = today.toString(),
            sleepHours = sleepHours,
            sleepStart = sleeps.minOfOrNull { it.startTime.toEpochMilli() },
            sleepEnd = sleeps.maxOfOrNull { it.endTime.toEpochMilli() },
            steps = steps,
            workoutMin = if (workouts.isEmpty()) null else workoutMin,
            naps = naps,
        )
        val summary = buildString {
            append(sleepHours?.let { "Sleep %.1fh".format(it) } ?: "No sleep record")
            if (naps.isNotEmpty()) append(" · ${naps.size} nap(s), ${naps.sumOf { it.minutes }}m")
            append(" · ${workouts.size} workout(s)")
            steps?.let { append(" · $it steps") }
        }
        return Readout(dc, summary)
    }
}
