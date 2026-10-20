package com.maven.notafraud.core.seed

import com.maven.notafraud.core.model.Anchor
import com.maven.notafraud.core.model.AnchorKind
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.CalEvent
import com.maven.notafraud.core.model.Energy
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.Project
import com.maven.notafraud.core.model.ProjectKind
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.plan.Time
import java.time.LocalDate
import java.time.ZoneId

/**
 * Starting data: Maven's weekly anchors, courses and remaining Fall 2026 exams (STATE.md section 7).
 * Everything here is editable in the app; this only runs on first launch.
 */
object Seed {
    private const val SEM_START = "2026-08-24"
    private const val SEM_END = "2026-12-11"

    /** Thanksgiving week: classes skipped. Adjust in Setup if the break differs. */
    private val THANKSGIVING = setOf("2026-11-23", "2026-11-24", "2026-11-25", "2026-11-26", "2026-11-27")

    private val RESEARCH_DATES = listOf(
        "2026-10-17", "2026-10-18", "2026-10-24", "2026-10-31",
        "2026-11-07", "2026-11-21", "2026-11-22", "2026-12-05",
    )

    private const val MON = 1; private const val TUE = 2; private const val WED = 3
    private const val THU = 4; private const val FRI = 5; private const val SAT = 6; private const val SUN = 7
    private val ALL = setOf(MON, TUE, WED, THU, FRI, SAT, SUN)

    private fun t(h: Int, m: Int = 0) = h * 60 + m

    fun anchors(): List<Anchor> {
        fun cls(title: String, days: Set<Int>, s: Int, e: Int) = Anchor(
            title = title, kind = AnchorKind.CLASS, startMin = s, endMin = e, days = days,
            fromDate = SEM_START, untilDate = SEM_END, skipDates = THANKSGIVING,
        )
        val research = RESEARCH_DATES.flatMap { d ->
            listOf(
                Anchor(title = "Biosensor: load 1h / 4h / 8h groups", kind = AnchorKind.RESEARCH, startMin = t(8), endMin = t(8, 45), dates = setOf(d)),
                Anchor(title = "Biosensor: test 1h group", kind = AnchorKind.RESEARCH, startMin = t(9), endMin = t(9, 45), dates = setOf(d)),
                Anchor(title = "Biosensor: test 4h group", kind = AnchorKind.RESEARCH, startMin = t(12), endMin = t(12, 45), dates = setOf(d)),
                Anchor(title = "Biosensor: test 8h group", kind = AnchorKind.RESEARCH, startMin = t(16), endMin = t(16, 45), dates = setOf(d)),
            )
        }
        return listOf(
            Anchor(title = "Cardio + shower", kind = AnchorKind.FITNESS, startMin = t(6), endMin = t(7), days = ALL),
            cls("ME 3411 Dynamic Systems", setOf(TUE, THU), t(8), t(9, 15)),
            cls("ME 3521 Applied Thermo", setOf(TUE, THU), t(9, 30), t(10, 45)),
            cls("ME 3525 Heat Transfer", setOf(TUE, THU), t(11), t(12, 15)),
            cls("BIO SCI 3333 A&P", setOf(MON, WED, FRI), t(11), t(11, 50)),
            cls("ME 4840 Lab", setOf(MON, WED), t(14), t(14, 50)),
            cls("ME 4840 Lab", setOf(TUE), t(15), t(16, 50)),
            Anchor(title = "Market open", kind = AnchorKind.MARKET, startMin = t(8, 30), endMin = t(9, 30), days = setOf(MON, WED, FRI)),
            Anchor(title = "Lunch", kind = AnchorKind.ROUTINE, startMin = t(12, 15), endMin = t(12, 45), days = ALL),
            Anchor(title = "Lift", kind = AnchorKind.FITNESS, startMin = t(17), endMin = t(18), days = setOf(MON, TUE, WED)),
            Anchor(title = "Dinner", kind = AnchorKind.ROUTINE, startMin = t(18, 15), endMin = t(18, 45), days = setOf(MON, TUE, WED, THU, SAT)),
            Anchor(title = "Craft block: build", kind = AnchorKind.CRAFT, startMin = t(16), endMin = t(18), days = setOf(FRI)),
            Anchor(title = "Date night", kind = AnchorKind.PROTECTED, startMin = t(18), endMin = t(23, 59), days = setOf(FRI), protected = true),
            Anchor(title = "Craft block: build", kind = AnchorKind.CRAFT, startMin = t(8), endMin = t(12), days = setOf(SAT), yieldsToDated = true),
            Anchor(title = "Weekly review", kind = AnchorKind.REVIEW, startMin = t(9), endMin = t(10), days = setOf(SUN), yieldsToDated = true),
            Anchor(title = "CSWE practice", kind = AnchorKind.ROUTINE, startMin = t(10), endMin = t(11, 30), days = setOf(SUN), yieldsToDated = true),
            Anchor(title = "Scout manual: one section", kind = AnchorKind.ROUTINE, startMin = t(13), endMin = t(13, 45), days = setOf(SUN), yieldsToDated = true),
            Anchor(title = "Off: work stops", kind = AnchorKind.PROTECTED, startMin = t(17), endMin = t(23, 59), days = setOf(SUN), protected = true),
        ) + research
    }

    fun data(now: Long, zoneId: String = "America/Chicago"): AppData {
        val zone = ZoneId.of(zoneId)
        val p = linkedMapOf(
            "me3411" to Project(name = "ME 3411 Dynamic Systems", kind = ProjectKind.SCHOOL),
            "me3521" to Project(name = "ME 3521 Applied Thermo", kind = ProjectKind.SCHOOL),
            "me3525" to Project(name = "ME 3525 Heat Transfer", kind = ProjectKind.SCHOOL),
            "bio" to Project(name = "BIO SCI 3333 A&P", kind = ProjectKind.SCHOOL),
            "me4840" to Project(name = "ME 4840 Lab", kind = ProjectKind.SCHOOL),
            "pt" to Project(name = "PT device", kind = ProjectKind.PROJECT),
            "biosensor" to Project(name = "Biosensor research", kind = ProjectKind.RESEARCH),
            "work" to Project(name = "Engineering tech job", kind = ProjectKind.PROJECT),
            "naf" to Project(name = "Not a Fraud", kind = ProjectKind.PROJECT),
            "side" to Project(name = "Side projects", kind = ProjectKind.PROJECT),
            "admin" to Project(name = "Admin", kind = ProjectKind.ADMIN),
            "life" to Project(name = "Life", kind = ProjectKind.LIFE),
        )

        fun at(date: String, h: Int, m: Int = 0) = Time.at(LocalDate.parse(date), h * 60 + m, zone)

        fun exam(title: String, proj: String, date: String, h: Int = 8, m: Int = 0, est: Int = 360) = Task(
            title = title, projectId = p.getValue(proj).id, estMin = est, dueAt = at(date, h, m),
            priority = Priority.HIGH, energy = Energy.HIGH, minBlockMin = 45,
            notBefore = at(date, h, m) - 10 * Time.DAY, createdAt = now,
        )

        val tasks = listOf(
            exam("Study: A&P exam (Oct 21)", "bio", "2026-10-21", est = 240),
            exam("Study: A&P exam (Nov 13)", "bio", "2026-11-13"),
            exam("Study: ME 3411 exam (Nov 17)", "me3411", "2026-11-17", est = 480),
            exam("Study: Heat Transfer exam (Nov 17)", "me3525", "2026-11-17"),
            exam("Study: A&P final (Dec 11)", "bio", "2026-12-11", est = 480),
            exam("Study: Heat Transfer final (Dec 15)", "me3525", "2026-12-15", est = 480),
            exam("Study: ME 3411 final (Dec 16)", "me3411", "2026-12-16", 12, 30, est = 600),
            Task(
                title = "Email Thermo + Instrumentation profs for exam dates", projectId = p.getValue("admin").id,
                estMin = 15, dueAt = at("2026-10-22", 17), priority = Priority.HIGH, energy = Energy.LOW, minBlockMin = 10, createdAt = now,
            ),
            Task(
                title = "Not a Fraud: install + grant permissions", projectId = p.getValue("naf").id,
                estMin = 45, dueAt = at("2026-10-23", 18), priority = Priority.NORMAL, energy = Energy.LOW, minBlockMin = 30, createdAt = now,
            ),
            Task(
                title = "PT device: next design step", projectId = p.getValue("pt").id,
                estMin = 120, priority = Priority.HIGH, energy = Energy.HIGH, minBlockMin = 60, createdAt = now,
            ),
        ).filter { it.dueAt == null || it.dueAt > now }

        val events = listOf(
            CalEvent(uid = "seed-me3411-final", title = "ME 3411 final", start = at("2026-12-16", 12, 30), end = at("2026-12-16", 14, 30), source = "seed"),
        )

        return AppData(
            projects = p.values.toList(),
            tasks = tasks,
            events = events,
            anchors = anchors(),
            seeded = true,
        )
    }
}
