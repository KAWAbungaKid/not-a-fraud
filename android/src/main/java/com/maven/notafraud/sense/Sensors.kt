package com.maven.notafraud.sense

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.Settings
import com.maven.notafraud.Graph
import com.maven.notafraud.core.model.CalEvent
import com.maven.notafraud.core.model.UsageDay
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.guard.GuardService
import java.util.concurrent.ConcurrentHashMap

object AppLabels {
    private val cache = ConcurrentHashMap<String, String>()

    fun label(ctx: Context, pkg: String): String = cache.getOrPut(pkg) {
        runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
    }

    data class Installed(val pkg: String, val label: String)

    /** Apps with a launcher icon, for the block/focus pickers. */
    fun launchable(ctx: Context): List<Installed> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filterNot { it.startsWith("com.maven.notafraud") }
            .map { Installed(it, label(ctx, it)) }
            .sortedBy { it.label.lowercase() }
    }
}

/** Per-app foreground time from UsageStatsManager. Queried on demand, never polled. */
object UsageReader {
    fun today(ctx: Context): Map<String, Int> {
        if (!Perms.usageAccess(ctx)) return emptyMap()
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val start = Graph.startOfToday()
        val end = Graph.now()
        val events = usm.queryEvents(start, end)
        val resumedAt = HashMap<String, Long>()
        val totals = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> resumedAt[e.packageName] = e.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val s = resumedAt.remove(e.packageName) ?: continue
                    totals[e.packageName] = (totals[e.packageName] ?: 0L) + (e.timeStamp - s)
                }
            }
        }
        // Still open now.
        resumedAt.forEach { (p, s) -> totals[p] = (totals[p] ?: 0L) + (end - s) }
        return totals.mapValues { (it.value / Time.MIN).toInt() }.filterValues { it >= 1 }
            .filterKeys { it !in com.maven.notafraud.core.guard.GuardPolicy.SAFE }
    }

    fun snapshot(ctx: Context): UsageDay {
        val mins = today(ctx)
        return UsageDay(
            date = Graph.today().toString(),
            device = "phone",
            minutesByApp = mins,
            labels = mins.keys.associateWith { AppLabels.label(ctx, it) },
        )
    }
}

/** Reads the phone's own calendar (Google Calendar syncs into it — no CORS, no connector needed). */
object PhoneCalendar {
    const val SOURCE = "phone"

    fun read(ctx: Context, daysBack: Int = 1, daysAhead: Int = 21): List<CalEvent>? {
        if (!Perms.calendar(ctx)) return null
        val zone = Graph.zone
        val from = Time.at(Graph.today().minusDays(daysBack.toLong()), 0, zone)
        val to = Time.at(Graph.today().plusDays(daysAhead.toLong()), 0, zone)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.AVAILABILITY,
        )
        val out = mutableListOf<CalEvent>()
        runCatching {
            ctx.contentResolver.query(uri, proj, "${CalendarContract.Instances.VISIBLE} = 1", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val status = c.getInt(5)
                    if (status == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    if (c.getInt(6) == CalendarContract.Instances.AVAILABILITY_FREE) continue
                    val begin = c.getLong(2)
                    out += CalEvent(
                        uid = "phone-${c.getLong(0)}-$begin",
                        title = c.getString(1) ?: "(busy)",
                        start = begin,
                        end = c.getLong(3),
                        source = SOURCE,
                        allDay = c.getInt(4) == 1,
                    )
                }
            }
        }
        return out
    }
}

object Perms {
    fun accessibility(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, GuardService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun usageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun notificationListener(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, MirrorService::class.java).flattenToString()
        return flat.split(':').any { it.equals(me, ignoreCase = true) }
    }

    fun notifications(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun calendar(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun exactAlarms(ctx: Context) = ctx.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()

    fun batteryUnrestricted(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
}
