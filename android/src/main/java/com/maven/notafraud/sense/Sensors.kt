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

    fun label(ctx: Context, pkg: String): String = if (pkg.startsWith(com.maven.notafraud.core.guard.Sites.PREFIX)) com.maven.notafraud.core.guard.Sites.label(pkg) else cache.getOrPut(pkg) {
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

    data class Cal(val id: Long, val name: String, val account: String?, val writable: Boolean, val visible: Boolean = true, val synced: Boolean = true, val accountType: String? = null)

    /**
     * A calendar Google Calendar doesn't sync to the phone (its "Sync" switch is off: the Google Calendar app can still
     * show it from the web, but no other app sees a single entry). Flip that switch for it and ask for a sync now.
     */
    fun enableSync(ctx: Context, cal: Cal): Boolean = runCatching {
        val v = android.content.ContentValues().apply {
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
        }
        val n = ctx.contentResolver.update(ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, cal.id), v, null, null)
        if (cal.account != null && cal.accountType != null) {
            val extras = android.os.Bundle().apply {
                putBoolean(android.content.ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(android.content.ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            runCatching { android.content.ContentResolver.requestSync(android.accounts.Account(cal.account, cal.accountType), CalendarContract.AUTHORITY, extras) }
        }
        n > 0
    }.getOrDefault(false)

    private val autoEnabled = HashSet<Long>()

    fun calendars(ctx: Context): List<Cal> {
        if (!Perms.calendar(ctx)) return emptyList()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.SYNC_EVENTS,
            CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val out = mutableListOf<Cal>()
        runCatching {
            ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    out += Cal(
                        id = c.getLong(0),
                        name = c.getString(1) ?: "(calendar)",
                        account = c.getString(2),
                        writable = c.getInt(3) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                        visible = c.getInt(4) == 1,
                        synced = c.getInt(5) == 1,
                        accountType = c.getString(6),
                    )
                }
            }
        }
        return out.sortedWith(compareBy({ !it.name.contains("fraud", ignoreCase = true) }, { it.name.lowercase() }))
    }

    /** How a calendar's entries are used. Canvas-like names default to deadlines. */
    fun modeFor(cal: Cal, settings: com.maven.notafraud.core.model.Settings): com.maven.notafraud.core.model.CalMode {
        settings.calendarModes[cal.id]?.let { return it }
        if (cal.id in settings.excludedCalendarIds || cal.id == settings.outputCalendarId) return com.maven.notafraud.core.model.CalMode.IGNORE
        val n = (cal.name + " " + (cal.account ?: "")).lowercase()
        return when {
            listOf("canvas", "instructure", "assignment", "coursework", "homework").any { it in n } -> com.maven.notafraud.core.model.CalMode.HOMEWORK
            listOf("test", "exam").any { it in cal.name.lowercase() } -> com.maven.notafraud.core.model.CalMode.TESTS
            else -> com.maven.notafraud.core.model.CalMode.EVENTS
        }
    }

    data class Split(val events: List<CalEvent>, val deadlines: List<CalEvent>, val tests: List<CalEvent>, val homework: List<CalEvent> = emptyList())

    /** Reads every calendar once and splits by mode: events to plan around, deadlines to turn into tasks. */
    fun readSplit(ctx: Context): Split? {
        val settings = Graph.data.settings
        val cals = calendars(ctx)
        val modes = cals.associate { it.id to modeFor(it, settings) }
        // Homework / deadline / test calendars must sync to the phone or nothing can be read: switch it on (once per run).
        for (c in cals) {
            val m = modes[c.id]
            if (!c.synced && m != null && m != com.maven.notafraud.core.model.CalMode.EVENTS && m != com.maven.notafraud.core.model.CalMode.IGNORE && autoEnabled.add(c.id)) enableSync(ctx, c)
        }
        // Events calendars: only what's shown and busy. Homework / deadlines / tests: every entry, even from a calendar
        // hidden in Google Calendar or marked "free" (Canvas entries often are) — 0.6 dropped those, so no homework.
        val raw = readRaw(ctx, daysAhead = 60) ?: return null
        val all = raw.filter { r ->
            val m = modes[r.ev.calendarId]
            if (m == com.maven.notafraud.core.model.CalMode.EVENTS || m == null) r.visible && !r.free && !r.declined else !r.declined
        }.map { it.ev }
        lastCounts = all.groupingBy { it.calendarId ?: -1L }.eachCount()
        lastReadAt = System.currentTimeMillis()
        val tests = all.filter { modes[it.calendarId] == com.maven.notafraud.core.model.CalMode.TESTS }
        // Tests are fixed time too: they block the plan like any event.
        val events = all.filter { modes[it.calendarId] == com.maven.notafraud.core.model.CalMode.EVENTS } + tests
        val deadlines = all.filter { modes[it.calendarId] == com.maven.notafraud.core.model.CalMode.DEADLINES }
            .map { it.copy(uid = it.uid.substringBeforeLast('-')) } // one task per event, not per instance
        val homework = all.filter { modes[it.calendarId] == com.maven.notafraud.core.model.CalMode.HOMEWORK }
            .map { it.copy(uid = it.uid.substringBeforeLast('-')) }
        return Split(events, deadlines, tests, homework)
    }

    /**
     * Raw instances from every visible calendar except the app's own output calendar.
     * [readSplit] decides what each one is.
     */
    /** Entries per calendar from the last read (next 60 days), for the Calendar page. */
    @Volatile var lastCounts: Map<Long, Int> = emptyMap()
    @Volatile var lastReadAt = 0L

    data class Raw(val ev: CalEvent, val visible: Boolean, val free: Boolean, val declined: Boolean)

    fun read(ctx: Context, daysBack: Int = 1, daysAhead: Int = 35): List<CalEvent>? =
        readRaw(ctx, daysBack, daysAhead)?.filter { it.visible && !it.free && !it.declined }?.map { it.ev }

    fun readRaw(ctx: Context, daysBack: Int = 1, daysAhead: Int = 35): List<Raw>? {
        if (!Perms.calendar(ctx)) return null
        val settings = Graph.data.settings
        val skip = listOfNotNull(settings.outputCalendarId)
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
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.DESCRIPTION,
        )
        val out = mutableListOf<Raw>()
        runCatching {
            ctx.contentResolver.query(uri, proj + CalendarContract.Instances.VISIBLE, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(7) in skip) continue
                    if ((c.getString(8) ?: "").contains(CalendarWriter.MARKER)) continue
                    val declined = c.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                    val free = c.getInt(6) == CalendarContract.Instances.AVAILABILITY_FREE
                    val visible = c.getInt(9) == 1
                    val begin = c.getLong(2)
                    out += Raw(CalEvent(
                        uid = "phone-${c.getLong(0)}-$begin",
                        title = c.getString(1) ?: "(busy)",
                        start = begin,
                        end = c.getLong(3),
                        source = SOURCE,
                        allDay = c.getInt(4) == 1,
                        calendarId = c.getLong(7),
                    ), visible, free, declined)
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

    fun calendarWrite(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun exactAlarms(ctx: Context) = ctx.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()

    fun batteryUnrestricted(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
}
