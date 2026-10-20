package com.maven.notafraud.core

import com.maven.notafraud.core.ics.IcsParser
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.seed.Seed
import com.maven.notafraud.core.store.Actions
import com.maven.notafraud.core.store.DataStore
import java.io.File
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StoreAndIcsTest {
    private val zone = ZoneId.of("America/Chicago")

    @Test
    fun `store round trips through disk`() {
        val dir = createTempDirectory()
        val f = File(dir, "data.json")
        val s1 = DataStore(f) { Seed.data(System.currentTimeMillis()) }
        s1.update { Actions.addTask(it, Task(title = "round trip")) }
        val s2 = DataStore(f) { AppData() }
        assertTrue(s2.current.tasks.any { it.title == "round trip" })
        assertTrue(s2.current.anchors.isNotEmpty())
    }

    @Test
    fun `session stop logs time and earns credits`() {
        val now = 1_800_000_000_000L
        val t = Task(title = "HW", estMin = 60, createdAt = now)
        var d = AppData(tasks = listOf(t))
        val before = d.ledger.balanceMin
        d = Actions.startSession(d, t.id, null, now)
        d = Actions.stopSession(d, now + 25 * Time.MIN)
        assertEquals(25, d.tasks.first().loggedMin)
        assertEquals(1, d.sessions.size)
        assertEquals(before + 25, d.ledger.balanceMin, 1e-9)
    }

    @Test
    fun `ics parses utc, tzid, floating and all-day with folding`() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:a1
            DTSTART:20261020T140000Z
            DTEND:20261020T150000Z
            SUMMARY:UTC event
            END:VEVENT
            BEGIN:VEVENT
            UID:a2
            DTSTART;TZID=America/New_York:20261020T100000
            DTEND;TZID=America/New_York:20261020T110000
            SUMMARY:Long title that
             continues
            END:VEVENT
            BEGIN:VEVENT
            UID:a3
            DTSTART:20261020T090000
            SUMMARY:Floating
            END:VEVENT
            BEGIN:VEVENT
            UID:a4
            DTSTART;VALUE=DATE:20261021
            SUMMARY:All day
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val ev = IcsParser.parse(ics, zone).associateBy { it.uid }
        assertEquals(4, ev.size)
        assertEquals(Time.MIN * 60, ev.getValue("a1").end - ev.getValue("a1").start)
        assertEquals("Long title thatcontinues", ev.getValue("a2").title)
        assertEquals(ev.getValue("a1").start, ev.getValue("a2").start) // 10:00 EDT == 14:00Z
        assertEquals(9 * 60, Time.minuteOfDay(ev.getValue("a3").start, zone))
        assertTrue(ev.getValue("a4").allDay)
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("naf").toFile()
}
