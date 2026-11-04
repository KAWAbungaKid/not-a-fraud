package com.maven.workout.link

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import com.maven.workout.core.Session
import java.time.Instant
import java.time.ZoneId

/**
 * Finished workouts go to Health Connect as strength-training sessions, so Samsung Health and the watch see them.
 * The session id is the client record id, so editing or re-saving a workout replaces it instead of doubling it.
 * Not a Fraud ignores records from this app (they're lifts, not cardio).
 */
object HealthWrite {
    val WRITE = HealthPermission.getWritePermission(ExerciseSessionRecord::class)

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(ctx: Context): Boolean = available(ctx) &&
        runCatching { WRITE in HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions() }.getOrDefault(false)

    suspend fun write(ctx: Context, s: Session): Boolean {
        val end = s.endedAt ?: return false
        if (!granted(ctx)) return false
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(s.startedAt)
        val stop = Instant.ofEpochMilli(end)
        val record = ExerciseSessionRecord(
            startTime = start, startZoneOffset = zone.rules.getOffset(start),
            endTime = stop, endZoneOffset = zone.rules.getOffset(stop),
            metadata = Metadata.manualEntry(clientRecordId = "trog-${s.id}", clientRecordVersion = System.currentTimeMillis()),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            title = s.name,
        )
        return runCatching { HealthConnectClient.getOrCreate(ctx).insertRecords(listOf(record)); true }.getOrDefault(false)
    }
}
