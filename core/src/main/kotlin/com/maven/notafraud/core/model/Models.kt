package com.maven.notafraud.core.model

import com.maven.notafraud.core.plan.DayPlan
import kotlinx.serialization.Serializable
import java.util.UUID

/*
 * Data model. Ported from the Project Max web app (STATE.md section 2).
 * Instants are epoch milliseconds; calendar dates are ISO strings ("2026-10-20");
 * times of day are minutes after midnight. Nothing here imports Android.
 */

fun newId(): String = UUID.randomUUID().toString().take(12)

@Serializable
enum class ProjectKind { SCHOOL, PROJECT, RESEARCH, ADMIN, LIFE }

@Serializable
enum class Priority { HIGH, NORMAL, LOW }

@Serializable
enum class Energy { LOW, MED, HIGH }

@Serializable
enum class TaskStatus { TODO, DONE }

/** Top of the hierarchy: what a life well spent looks like, in Maven's words. Never scheduled. */
@Serializable
data class Goal(
    val id: String = newId(),
    val name: String,
    val order: Int = 0,
)

/**
 * A part of life kept up forever, with no finish line (School, Fitness, Faith...).
 * Feeds one or more goals. Projects live under exactly one area.
 */
@Serializable
data class Area(
    val id: String = newId(),
    val name: String,
    val goalIds: Set<String> = emptySet(),
    val order: Int = 0,
    val archived: Boolean = false,
)

@Serializable
data class Project(
    val id: String = newId(),
    val name: String,
    val kind: ProjectKind,
    val archived: Boolean = false,
    val areaId: String? = null,
)

@Serializable
data class Task(
    val id: String = newId(),
    val title: String,
    val projectId: String? = null,
    val estMin: Int = 30,
    val dueAt: Long? = null,
    val priority: Priority = Priority.NORMAL,
    val energy: Energy = Energy.MED,
    val minBlockMin: Int = 15,
    val status: TaskStatus = TaskStatus.TODO,
    val loggedMin: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastWorkedAt: Long? = null,
    val doneAt: Long? = null,
    /** The planner will not place this task before this instant (Friday-craft rule, snoozes). */
    val notBefore: Long? = null,
    val icsUid: String? = null,
    val notes: String = "",
    /** Pinned hot by hand: boosted in the planner. Deadline heat is computed, not stored. */
    val hot: Boolean = false,
    /** Set when the app generated this task (e.g. "makeup:<class>:<date>", "exam:<uid>"), so it is created once. */
    val sourceKey: String? = null,
)

@Serializable
data class CalEvent(
    val uid: String,
    val title: String,
    val start: Long,
    val end: Long,
    val source: String = "ics",
    val allDay: Boolean = false,
)

@Serializable
data class WorkSession(
    val id: String = newId(),
    val taskId: String? = null,
    val projectId: String? = null,
    val title: String,
    val start: Long,
    val end: Long,
    val minutes: Int,
)

@Serializable
data class ActiveSession(
    val taskId: String? = null,
    val projectId: String? = null,
    val title: String,
    val start: Long,
)

/** A side-project idea captured midweek. It becomes a task that can't be scheduled before the Friday craft block. */
@Serializable
data class Idea(
    val id: String = newId(),
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
    val promotedTaskId: String? = null,
)

@Serializable
enum class AnchorKind { CLASS, FITNESS, MARKET, RESEARCH, REVIEW, CRAFT, PROTECTED, ROUTINE }

/**
 * How firmly a block holds its time.
 * HARD: never moves (classes, trading, date night, calendar events).
 * HABIT: keeps its slot so the rhythm sticks; can give way to a hot deadline if [Anchor.yieldsToHot].
 * CONTAINER: a slot filled with the hottest task from its areas (craft blocks).
 */
@Serializable
enum class AnchorMode { HARD, HABIT, CONTAINER }

/**
 * A fixed block the planner places before anything else.
 * Either recurs on [days] (1 = Monday … 7 = Sunday) between [fromDate] and [untilDate],
 * or happens only on the explicit [dates].
 */
@Serializable
data class Anchor(
    val id: String = newId(),
    val title: String,
    val kind: AnchorKind,
    val startMin: Int,
    val endMin: Int,
    val days: Set<Int> = emptySet(),
    val dates: Set<String> = emptySet(),
    val skipDates: Set<String> = emptySet(),
    /** Recurring anchors on these dates are replaced by the date-specific anchors that day (e.g. research Saturdays). */
    val fromDate: String? = null,
    val untilDate: String? = null,
    /** Protected time: no tasks, no nags, no blocking escalation. Date night, Sunday evening. */
    val protected: Boolean = false,
    /** If true and a dated anchor overlaps it, this recurring anchor is dropped that day. */
    val yieldsToDated: Boolean = false,
    val enabled: Boolean = true,
    val mode: AnchorMode = AnchorMode.HARD,
    /** HABIT/CONTAINER only: a task with a deadline closing in can take this slot. */
    val yieldsToHot: Boolean = false,
    /** CONTAINER only: fill with tasks from these areas. Empty = project-kind tasks. */
    val fillAreaIds: Set<String> = emptySet(),
)

@Serializable
enum class RoutineCategory { MEAL, HYGIENE, BODY, SLEEP, OTHER }

@Serializable
enum class RoutineTrigger {
    /** Escalates by clock: window start, due, angry. */
    TIME,
    /** Escalates by clock, and immediately when the phone looks like it's going to bed (charging / screen off late). */
    BEDTIME,
}

/**
 * A daily habit with a window that gets angrier the longer it's put off.
 * Before [startMin]: calm. Until [dueMin]: due. Until [angryMin]: late (one nudge). After: angry (every 30 min).
 * If [reserveMin] > 0 the planner also blocks that much time at [startMin] (cardio, workout, meals).
 */
@Serializable
data class RoutineItem(
    val id: String = newId(),
    val title: String,
    val category: RoutineCategory = RoutineCategory.OTHER,
    val trigger: RoutineTrigger = RoutineTrigger.TIME,
    val startMin: Int,
    val dueMin: Int,
    val angryMin: Int,
    val days: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val reserveMin: Int = 0,
    val enabled: Boolean = true,
    /** Times per week this should happen (workouts: 6). Null = every listed day. */
    val weeklyTarget: Int? = null,
    val order: Int = 0,
)

/** What got done on one day. Times are epoch ms. */
@Serializable
data class RoutineDay(
    val date: String,
    val done: Map<String, Long> = emptyMap(),
    /** Free-text note per item, e.g. "climbing" on a workout. */
    val notes: Map<String, String> = emptyMap(),
    /** Last nudge per item, so alarms firing late or twice don't double-nag. */
    val alerted: Map<String, Long> = emptyMap(),
)

@Serializable
data class AppRules(
    /** Packages that are blocked unless unlocked with credits. */
    val blocked: Set<String> = DEFAULT_BLOCKED,
    /** Packages that earn credits while in the foreground. */
    val focus: Set<String> = DEFAULT_FOCUS,
    /** Packages never touched (launcher, phone, this app...). Added to built-in safe list. */
    val alwaysAllowed: Set<String> = emptySet(),
    /** Packages whose notifications are mirrored into the inbox. */
    val mirrored: Set<String> = setOf("com.snapchat.android"),
    /** Windows process names (lowercase, e.g. "discord.exe") treated as distractions on the PC. */
    val desktopBlockedProcesses: Set<String> = emptySet(),
    /** Window-title keywords treated as distractions on the PC (catches YouTube in Chrome). */
    val desktopBlockedTitles: Set<String> = setOf("youtube", "instagram", "reddit", "tiktok", "twitter", " / x"),
    val blockingEnabled: Boolean = true,
    /** Websites blocked inside browsers (matched against the address bar host and its parents). */
    val blockedSites: Set<String> = DEFAULT_SITES,
) {
    companion object {
        val DEFAULT_BLOCKED = setOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.twitter.android",
            "com.reddit.frontpage",
            "com.google.android.youtube",
            "com.snapchat.android",
            "com.facebook.katana",
        )
        val DEFAULT_SITES = setOf(
            "instagram.com", "tiktok.com", "x.com", "twitter.com", "reddit.com", "youtube.com", "facebook.com",
        )
        val DEFAULT_FOCUS = setOf(
            "com.amazon.kindle",
            "com.google.android.apps.books",
            "com.ichi2.anki",
            "com.overdrive.mobile.android.libby",
            "com.adobe.reader",
            "com.samsung.android.app.notes",
        )
    }
}

@Serializable
data class LedgerEntry(
    val at: Long,
    val deltaMin: Double,
    val reason: String,
)

@Serializable
data class Ledger(
    val balanceMin: Double = 15.0,
    val entries: List<LedgerEntry> = emptyList(),
    /** Package -> epoch ms until which it is unlocked. "*" means everything. */
    val unlockedUntil: Map<String, Long> = emptyMap(),
    val emergencyUses: List<Long> = emptyList(),
)

@Serializable
data class Settings(
    /** Planner fills tasks between these. Bedtime routine items live after [dayEndMin]. */
    val dayStartMin: Int = 7 * 60,
    val dayEndMin: Int = 21 * 60 + 30,
    val maxChunkMin: Int = 90,
    val breakMin: Int = 10,
    val badSleepHours: Double = 6.0,
    val energy: Energy = Energy.MED,
    val zoneId: String = "America/Chicago",
    val creed: String = "Not a fraud. Do the next right thing.",
    val earnPerFocusMin: Double = 1.0,
    val earnPerSessionMin: Double = 1.0,
    val maxBalanceMin: Double = 120.0,
    val emergencyUnlockMin: Int = 5,
    val emergencyWaitSec: Int = 60,
    val emergencyDailyLimit: Int = 3,
    val emergencyPhrase: String = "I am choosing this on purpose",
    val nagsEnabled: Boolean = true,
    val syncEnabled: Boolean = false,
    /** PC: minimize a distraction window after the second nudge. */
    val desktopMinimize: Boolean = false,
    val routineNagsEnabled: Boolean = true,
    /** Phone calendar id the app writes its plan into ("Not a Fraud" calendar created in Google Calendar). */
    val outputCalendarId: Long? = null,
    /** Phone calendar ids never read as events (old routine calendars, holidays...). */
    val excludedCalendarIds: Set<Long> = emptySet(),
)

/** Per-day context: how the day started. Drives the bad-day softening. */
@Serializable
data class DayContext(
    val date: String,
    val sleepHours: Double? = null,
    val sleepStart: Long? = null,
    val sleepEnd: Long? = null,
    val steps: Long? = null,
    val workoutMin: Int? = null,
    /** User said "rough day" by hand. */
    val markedRough: Boolean = false,
)

@Serializable
data class UsageDay(
    val date: String,
    val device: String,
    /** package or process -> minutes in foreground */
    val minutesByApp: Map<String, Int> = emptyMap(),
    val labels: Map<String, String> = emptyMap(),
)

@Serializable
data class MirroredMessage(
    val key: String,
    val pkg: String,
    val sender: String,
    val text: String,
    val at: Long,
    val read: Boolean = false,
)

@Serializable
data class AppData(
    val schema: Int = 1,
    val goals: List<Goal> = emptyList(),
    val areas: List<Area> = emptyList(),
    val routine: List<RoutineItem> = emptyList(),
    val routineDays: List<RoutineDay> = emptyList(),
    /** date ("2026-10-21") -> up to 3 task ids picked the evening before. */
    val picks: Map<String, List<String>> = emptyMap(),
    /** Calendar event uids the user said aren't exams worth a study task. */
    val ignoredExamUids: Set<String> = emptySet(),
    val projects: List<Project> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val events: List<CalEvent> = emptyList(),
    val sessions: List<WorkSession> = emptyList(),
    val ideas: List<Idea> = emptyList(),
    val anchors: List<Anchor> = emptyList(),
    val rules: AppRules = AppRules(),
    val ledger: Ledger = Ledger(),
    val settings: Settings = Settings(),
    val active: ActiveSession? = null,
    val days: List<DayContext> = emptyList(),
    val usage: List<UsageDay> = emptyList(),
    val messages: List<MirroredMessage> = emptyList(),
    val seeded: Boolean = false,
    /** Today's generated plan; regenerated on a new day or on Replan. */
    val todayPlan: DayPlan? = null,
)
