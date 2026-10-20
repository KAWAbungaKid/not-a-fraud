package com.maven.notafraud.core.ics

import com.maven.notafraud.core.model.CalEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Minimal iCalendar (.ics) reader: VEVENT with DTSTART/DTEND/SUMMARY/UID.
 * Handles line folding, UTC ("Z"), TZID, floating times (assumed [defaultZone]) and all-day dates.
 * RRULE is not expanded; recurring events should come in through the phone calendar instead.
 */
object IcsParser {
    private val dt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val d = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun parse(text: String, defaultZone: ZoneId): List<CalEvent> {
        val lines = unfold(text)
        val out = mutableListOf<CalEvent>()
        var inEvent = false
        var props = mutableMapOf<String, Pair<Map<String, String>, String>>()
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> { inEvent = true; props = mutableMapOf() }
                line == "END:VEVENT" -> {
                    inEvent = false
                    toEvent(props, defaultZone)?.let { out += it }
                }
                inEvent -> {
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val head = line.substring(0, colon)
                    val value = line.substring(colon + 1)
                    val parts = head.split(';')
                    val name = parts[0].uppercase()
                    val params = parts.drop(1).mapNotNull {
                        val eq = it.indexOf('=')
                        if (eq > 0) it.substring(0, eq).uppercase() to it.substring(eq + 1).trim('"') else null
                    }.toMap()
                    if (name !in props) props[name] = params to value
                }
            }
        }
        return out
    }

    private fun unfold(text: String): List<String> {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<String>()
        for (l in raw) {
            if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) out[out.size - 1] = out.last() + l.substring(1)
            else out += l
        }
        return out.map { it.trimEnd() }.filter { it.isNotEmpty() }
    }

    private fun toEvent(p: Map<String, Pair<Map<String, String>, String>>, zone: ZoneId): CalEvent? {
        val (sParams, sVal) = p["DTSTART"] ?: return null
        val start = parseTime(sParams, sVal, zone) ?: return null
        val allDay = sParams["VALUE"] == "DATE" || sVal.length == 8
        val endMs: Long = p["DTEND"]?.let { (ep, ev) -> parseTime(ep, ev, zone)?.first }
            ?: (if (allDay) start.first + 86_400_000L else start.first + 3_600_000L)
        val summary = p["SUMMARY"]?.second?.let(::unescape) ?: "(untitled)"
        val uid = p["UID"]?.second ?: "${summary}-${start.first}"
        return CalEvent(uid = uid, title = summary, start = start.first, end = endMs, source = "ics", allDay = allDay)
    }

    /** Returns (epochMs, isAllDay). */
    private fun parseTime(params: Map<String, String>, value: String, defaultZone: ZoneId): Pair<Long, Boolean>? = runCatching {
        if (params["VALUE"] == "DATE" || value.length == 8) {
            LocalDate.parse(value, d).atStartOfDay(defaultZone).toInstant().toEpochMilli() to true
        } else if (value.endsWith("Z")) {
            LocalDateTime.parse(value.dropLast(1), dt).toInstant(ZoneOffset.UTC).toEpochMilli() to false
        } else {
            val zone = params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: defaultZone
            LocalDateTime.parse(value, dt).atZone(zone).toInstant().toEpochMilli() to false
        }
    }.getOrNull()

    private fun unescape(s: String) = s.replace("\\n", " ").replace("\\N", " ").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
}
