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
import java.time.LocalDate

/**
 * Health Connect: sleep (night + naps), workouts and steps. Samsung Health writes into it.
 *
 * v0.5: each kind of data is read if *its* permission is granted (0.4 needed all three, so one denied
 * permission silently stopped sleep too), the last 3 days are filled in (not just today), background reads
 * ask for Health Connect's background permission, and [diagnose] shows exactly what Health Connect returns.
 */
object Health {
    val SLEEP = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val EXERCISE = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    val STEPS = HealthPermission.getReadPermission(StepsRecord::class)
    const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    val BASE: Set<String> = setOf(SLEEP, EXERCISE, STEPS)
    val PERMISSIONS: Set<String> = BASE + BACKGROUND

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    private fun client(ctx: Context) = HealthConnectClient.getOrCreate(ctx)

    suspend fun grantedSet(ctx: Context): Set<String> =
        if (!available(ctx)) emptySet() else runCatching { client(ctx).permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    /** Setup counts Health as done once sleep can be read. */
    suspend fun granted(ctx: Context): Boolean = SLEEP in grantedSet(ctx)

    data class Readout(val days: List<DayContext>, val summary: String)

    @Volatile var lastRead: Pair<Long, String>? = null
        private set

    suspend fun read(ctx: Context): Readout? {
        val g = grantedSet(ctx)
        if (g.isEmpty()) return null
        val c = client(ctx)
        val zone = Graph.zone
        val today = Graph.today()
        val now = Instant.ofEpochMilli(Graph.now())
        fun start(d: LocalDate) = Instant.ofEpochMilli(Time.at(d, 0, zone))

        val oldest = today.minusDays(2)
        val sessions = if (SLEEP in g) runCatching {
            c.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start(oldest).minus(Duration.ofHours(8)), now))).records
        }.getOrElse { lastRead = Graph.now() to "Sleep read failed: ${it.javaClass.simpleName} ${it.message.orEmpty()}"; emptyList() } else emptyList()

        val out = mutableListOf<DayContext>()
        var todaySummary = ""
        for (day in listOf(oldest, today.minusDays(1), today)) {
            val s0 = start(day)
            // Night = the longest session (2 h+) ending between 3am and 2pm that day. Naps = other 10 min–4 h sessions that day.
            val night = sessions.filter {
                it.endTime.isAfter(s0.plus(Duration.ofHours(3))) && it.endTime.isBefore(s0.plus(Duration.ofHours(14))) &&
                    Duration.between(it.startTime, it.endTime).toMinutes() >= 120
            }.maxByOrNull { Duration.between(it.startTime, it.endTime).toMillis() }
            val naps = sessions.filter { it !== night && it.startTime.isAfter(s0.plus(Duration.ofHours(6))) && it.startTime.isBefore(s0.plus(Duration.ofHours(24))) }
                .filter { Duration.between(it.startTime, it.endTime).toMinutes() in 10..240 }
                .map { com.maven.notafraud.core.model.Nap(it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
            val sleepHours = night?.let { Duration.between(it.startTime, it.endTime).toMillis() / 3_600_000.0 }

            val end = if (day == today) now else s0.plus(Duration.ofDays(1))
            val dayFilter = TimeRangeFilter.between(s0, end)
            // Workouts Not a Troglodyte wrote are lifts it already reports itself: they don't count as cardio here.
            val workouts = if (EXERCISE in g) runCatching { c.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, dayFilter)).records }.getOrDefault(emptyList())
                .filterNot { it.metadata.dataOrigin.packageName.startsWith("com.maven.workout") } else emptyList()
            val workoutMin = workouts.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt()
            val steps = if (STEPS in g) runCatching {
                c.aggregate(AggregateRequest(metrics = setOf(StepsRecord.COUNT_TOTAL), timeRangeFilter = dayFilter))[StepsRecord.COUNT_TOTAL]
            }.getOrNull() else null

            if (night == null && naps.isEmpty() && workouts.isEmpty() && steps == null) continue
            out += DayContext(
                date = day.toString(),
                sleepHours = sleepHours,
                sleepStart = night?.startTime?.toEpochMilli(),
                sleepEnd = night?.endTime?.toEpochMilli(),
                steps = steps,
                workoutMin = if (workouts.isEmpty()) null else workoutMin,
                naps = naps,
            )
            if (day == today) todaySummary = buildString {
                append(sleepHours?.let { "Sleep %.1fh".format(it) } ?: "No night sleep yet")
                if (naps.isNotEmpty()) append(" · ${naps.size} nap(s), ${naps.sumOf { it.minutes }}m")
                append(" · ${workouts.size} workout(s)")
                steps?.let { append(" · $it steps") }
            }
        }
        val summary = todaySummary.ifBlank { "Nothing from Health Connect today" } + " · ${sessions.size} sleep record(s) in 3 days"
        if (lastRead?.second?.startsWith("Sleep read failed") != true || sessions.isNotEmpty()) lastRead = Graph.now() to summary
        return Readout(out, summary)
    }

    /** Human-readable: what's granted, and every sleep record of the last 2 days with the app that wrote it. */
    suspend fun diagnose(ctx: Context): List<String> {
        val lines = mutableListOf<String>()
        when (HealthConnectClient.getSdkStatus(ctx)) {
            HealthConnectClient.SDK_AVAILABLE -> {}
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> return listOf("Health Connect needs an update (Play Store).")
            else -> return listOf("Health Connect isn't available on this phone.")
        }
        val g = grantedSet(ctx)
        fun yn(p: String) = if (p in g) "yes" else "NO"
        lines += "Allowed to read — sleep: ${yn(SLEEP)}, exercise: ${yn(EXERCISE)}, steps: ${yn(STEPS)}, in background: ${yn(BACKGROUND)}"
        if (SLEEP in g) {
            val now = Instant.ofEpochMilli(Graph.now())
            val recs = runCatching {
                client(ctx).readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(now.minus(Duration.ofHours(48)), now))).records
            }
            recs.onFailure { lines += "Reading sleep failed: ${it.javaClass.simpleName}: ${it.message}" }
            val list = recs.getOrDefault(emptyList())
            if (list.isEmpty() && recs.isSuccess) lines += "No sleep records in the last 48 h. Health Connect has nothing from Samsung Health yet (see the steps below)."
            val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE h:mm a", java.util.Locale.US).withZone(Graph.zone)
            for (r in list.sortedByDescending { it.endTime }) {
                val h = Duration.between(r.startTime, r.endTime).toMinutes() / 60.0
                lines += "Sleep ${fmt.format(r.startTime)} → ${fmt.format(r.endTime)} (%.1f h) from ${r.metadata.dataOrigin.packageName}".format(h)
            }
        }
        lastRead?.let { (at, s) -> lines += "Last sync ${java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.US).format(java.util.Date(at))}: $s" }
        return lines
    }
}
