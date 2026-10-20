package com.maven.notafraud.sense

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import com.maven.notafraud.Graph
import com.maven.notafraud.core.plan.BlockKind
import com.maven.notafraud.core.plan.PlanBlock
import com.maven.notafraud.core.plan.Time
import java.util.concurrent.Executors

/**
 * Mirrors today's plan into the user's "Not a Fraud" Google calendar, so the whole day shows up in
 * Google Calendar. Each event carries a marker with a key in its description; a sync deletes marked
 * events that are no longer in the plan and inserts missing ones. Events the user typed into that
 * calendar by hand (no marker) are never touched. Everything else in Google Calendar is read-only to
 * the app and is treated as fixed events.
 */
object CalendarWriter {
    const val MARKER = "[naf:"
    private const val TAG = "NafCalendar"
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var lastHash: Int? = null

    data class Want(val key: String, val title: String, val start: Long, val end: Long)

    fun syncAsync(ctx: Context) {
        val app = ctx.applicationContext
        exec.execute { runCatching { sync(app) }.onFailure { Log.w(TAG, "sync failed", it) } }
    }

    private fun desired(): List<Want> {
        val d = Graph.data
        val plan = d.todayPlan ?: return emptyList()
        val zone = Graph.zone
        val date = Graph.today()
        val events = d.events.filter { !it.allDay }
        fun overlapsEvent(b: PlanBlock) = events.any { e -> e.start < b.end && b.start < e.end && (minOf(e.end, b.end) - maxOf(e.start, b.start)) * 2 >= (b.end - b.start) }
        val out = mutableListOf<Want>()
        for (b in plan.blocks) {
            if (b.kind == BlockKind.EVENT || b.kind == BlockKind.BREAK) continue
            if (b.kind == BlockKind.ANCHOR && overlapsEvent(b)) continue
            val title = when {
                b.kind == BlockKind.TASK && b.picked -> "★ ${b.title}"
                b.kind == BlockKind.TASK -> "▸ ${b.title}"
                else -> b.title
            }
            out += Want("${b.kind}|${b.start}|${b.end}|${b.title}".hashCode().toString(16), title, b.start, b.end)
        }
        // Routine checkboxes without reserved time show as short reminders.
        for (r in d.routine) {
            if (!r.enabled || r.reserveMin > 0 || date.dayOfWeek.value !in r.days) continue
            val s = Time.at(date, r.startMin, zone)
            out += Want("R|${r.id}|$s".hashCode().toString(16), "• ${r.title}", s, s + 10 * Time.MIN)
        }
        return out
    }

    @Synchronized
    fun sync(ctx: Context) {
        val calId = Graph.data.settings.outputCalendarId ?: return
        if (!Perms.calendarWrite(ctx) || !Perms.calendar(ctx)) return
        val want = desired()
        val hash = (want.map { it.key }.sorted().joinToString() + calId).hashCode()
        if (hash == lastHash) return

        val zone = Graph.zone
        val dayStart = Time.at(Graph.today(), 0, zone)
        val dayEnd = Time.at(Graph.today().plusDays(1), 0, zone)
        val cr = ctx.contentResolver
        val existing = HashMap<String, Long>()
        cr.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.DESCRIPTION),
            "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ? AND ${CalendarContract.Events.DELETED} = 0",
            arrayOf(calId.toString(), dayStart.toString(), dayEnd.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val desc = c.getString(1) ?: continue
                val i = desc.indexOf(MARKER)
                if (i < 0) continue
                val key = desc.substring(i + MARKER.length).substringBefore(']')
                existing[key] = c.getLong(0)
            }
        }
        val wantKeys = want.map { it.key }.toSet()
        var removed = 0
        var added = 0
        for ((key, id) in existing) if (key !in wantKeys) {
            cr.delete(android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
            removed++
        }
        for (w in want) if (w.key !in existing) {
            val v = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, w.title)
                put(CalendarContract.Events.DTSTART, w.start)
                put(CalendarContract.Events.DTEND, w.end)
                put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
                put(CalendarContract.Events.DESCRIPTION, "Planned by Not a Fraud. Changes here are overwritten.\n$MARKER${w.key}]")
                put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_FREE)
                put(CalendarContract.Events.HAS_ALARM, 0)
            }
            cr.insert(CalendarContract.Events.CONTENT_URI, v)
            added++
        }
        lastHash = hash
        Log.i(TAG, "calendar sync: +$added -$removed (${want.size} planned)")
    }

    fun forget() { lastHash = null }
}
