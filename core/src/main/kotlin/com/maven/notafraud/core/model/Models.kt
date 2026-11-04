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

/**
 * What kind of time a task needs (STATE.md section 11).
 * FOCUS: full attention until done. STEPS: attended steps with unattended waits between (laundry, research runs).
 * PRACTICE: open-ended time-in toward a weekly target (skills, catch-up study). ERRAND: go somewhere.
 * WAITING: blocked on someone else, never planned until unblocked. FILLER: low-brain, good when tired.
 */
@Serializable
enum class TaskKind { FOCUS, STEPS, PRACTICE, ERRAND, WAITING, FILLER }

/** One step of a STEPS task. [waitBeforeMin] is unattended time after the previous step (washer running, samples exposing). */
@Serializable
data class TaskStep(
    val id: String = newId(),
    val title: String,
    val min: Int,
    val waitBeforeMin: Int = 0,
    /** Must start right when the wait ends (research timing). Otherwise any time after. */
    val exact: Boolean = false,
    val doneAt: Long? = null,
)

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
    /** 1–5 baseline for everything under this area (later driven by stats). */
    val importance: Int = 3,
    /** Stat this area feeds (body, mind, work, hygiene, discipline, social, spirit). A low stat raises the area. */
    val stat: String? = null,
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
    val kind: TaskKind = TaskKind.FOCUS,
    val steps: List<TaskStep> = emptyList(),
    /** PRACTICE: minutes per week to aim for. */
    val weeklyTargetMin: Int? = null,
    /** PRACTICE (and any kind): longest useful block. Null = planner default. */
    val maxBlockMin: Int? = null,
    /** 1–5; null = inherit the area's importance. */
    val importance: Int? = null,
    /** Bumped: +1 importance until this instant. */
    val bumpUntil: Long? = null,
    /** Each time it was pushed off ("not today", "later"). Neutral for now; patterns come later. */
    val deferrals: List<Long> = emptyList(),
    /** WAITING: who or what it's waiting on. */
    val waitingOn: String = "",
    /** To-do list level: ASAP / on a day / within dates / someday. Null = a normal project task. */
    val horizon: Horizon? = null,
    /** Aim to be done by this (homework: due minus the days-ahead goal). Urgency uses it; Heat still uses [dueAt]. */
    val targetAt: Long? = null,
    /** Most minutes per day the planner gives this task, so big assignments spread over days. */
    val dailyCapMin: Int? = null,
)

/** To-do list levels. */
@Serializable
enum class Horizon {
    /** As soon as possible: planned first, no date. */
    ASAP,
    /** On one day ([Task.notBefore] = that day's start, [Task.dueAt] = its end). */
    ON_DAY,
    /** Any time between [Task.notBefore] and [Task.dueAt]. */
    WITHIN,
    /** Something to try eventually: kept on the list, never planned. */
    SOMEDAY,
}

@Serializable
data class CalEvent(
    val uid: String,
    val title: String,
    val start: Long,
    val end: Long,
    val source: String = "ics",
    val allDay: Boolean = false,
    val calendarId: Long? = null,
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
    /** Dates skipped with "make up later" (a make-up task was created). */
    val madeUpDates: Set<String> = emptySet(),
)

@Serializable
enum class RoutineCategory {
    MEAL, HYGIENE, BODY, SLEEP, OTHER,
    /** Counts as done every day on its own until you say you broke it (no nags, no times). */
    STREAK,
}

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
    /** Checks itself off when this happens (no hard-coded item ids). */
    val autoCheck: AutoCheck? = null,
    /** MEAL items: the Not a Twig meal slot (Lunch, Supper…) that ticks this and drives its nags while Twig is connected. */
    val dietSlot: String? = null,
    /** STREAK items: the day the streak started counting (yyyy-mm-dd). */
    val streakSince: String? = null,
    /** Switched off because Not a Twig's meal schedule has no slot for it; switched back on if Twig goes away. */
    val pausedByDiet: Boolean = false,
)

/** What can tick a routine item on its own. */
@Serializable
enum class AutoCheck {
    /** Wake time from the watch, or the first unlock after 4am. */
    WAKE,
    /** 20+ min workout in Samsung Health, or 20+ min on a sport skill. Also what Stats and burnout count as a workout. */
    WORKOUT,
    /** Picking tomorrow's 3. */
    PICK3,
    /** A sport / climbing session (20+ min on a sport skill), or exercise from Samsung Health while Not a Troglodyte owns lifting. */
    CARDIO,
}

/** A change that loosens blocking, held until the morning (settings lock). One kind per field it touches. */
@Serializable
data class PendingChange(
    val id: String = newId(),
    val kind: ChangeKind,
    val label: String,
    val createdAt: Long,
    val applyAfter: Long,
    val pkg: String? = null,
    val items: Set<String> = emptySet(),
    val flag: Boolean? = null,
    val number: Int? = null,
    val text: String? = null,
    val profile: BlockProfile? = null,
    val notified: Boolean = false,
    /** Why you wanted it, typed at the time; shown back in the morning next to "still want this?". */
    val reason: String? = null,
)

@Serializable
enum class ChangeKind {
    BLOCKING, NAGS, ROUTINE_NAGS, UNBLOCK_APPS, UNBLOCK_SITES, FOCUS_APPS, PROFILE, EMERGENCY_MIN, EMERGENCY_PHRASE, LOCK, LOCK_TIME, WORKOUT_PAUSE, NUMBER,
    /** Ten minutes where the phone's Settings pages for Not a Fraud open normally. */
    SETTINGS_WINDOW,
    /** Switch off the guard on Not a Fraud's Settings pages. */
    SETTINGS_GUARD,
    /** Looser ladder (off, lower top level, bedtime brick off / shorter). [profile] unused; [ladder] holds it. */
    LADDER,
    /** More apps allowed at Level 3 / bedtime. */
    LADDER_ALLOW,
    /** Delete or switch off a routine item. */
    ROUTINE_REMOVE,
}

/**
 * The brick ladder (STATE.md § 22). Level 0 = normal; 1 = friction; 2 = dull (grayscale, unlock lands on Now);
 * 3 = brick (only [allowlist] opens). Bedtime is Level 3 from [bedMin] to [wakeMin].
 */
@Serializable
data class LadderSettings(
    val enabled: Boolean = true,
    val maxLevel: Int = 3,
    /** Apps that still open at Level 3 and at bedtime (phone, texts, Settings and Maven's apps always do). */
    val allowlist: Set<String> = emptySet(),
    /** Level 3 only happens once the list has been checked and saved. */
    val allowlistConfirmed: Boolean = false,
    /** Extra apps only at bedtime (alarm clock, white noise). */
    val bedtimeExtra: Set<String> = emptySet(),
    val bedtimeBrick: Boolean = true,
    val bedMin: Int = 22 * 60,
    val wakeMin: Int = 5 * 60 + 30,
    val grayscaleFrom: Int = 2,
    val unlockToNowFrom: Int = 2,
    /** Put the ladder and the next thing to do on the lock screen wallpaper. */
    val lockWallpaper: Boolean = false,
)

/** A school term: class blocks run between [start] and [end], except [offDates] (breaks). */
@Serializable
data class Semester(val name: String, val start: String, val end: String, val offDates: Set<String> = emptySet())

/** What got done on one day. Times are epoch ms. */
@Serializable
data class RoutineDay(
    val date: String,
    val done: Map<String, Long> = emptyMap(),
    /** Free-text note per item, e.g. "climbing" on a workout. */
    val notes: Map<String, String> = emptyMap(),
    /** Last nudge per item, so alarms firing late or twice don't double-nag. */
    val alerted: Map<String, Long> = emptyMap(),
    /** STREAK items broken on this day, and when that was said. */
    val broken: Map<String, Long> = emptyMap(),
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
    /** Per-app blocking profiles; apps without one use [defaultProfile]. */
    val profiles: Map<String, BlockProfile> = mapOf(
        "com.snapchat.android" to BlockProfile(quickOpen = true),
        "com.google.android.youtube" to BlockProfile(askPurpose = true, replyWindowMin = 0),
    ),
    val defaultProfile: BlockProfile = BlockProfile(),
) {
    fun profile(pkg: String) = profiles[pkg] ?: defaultProfile

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

/**
 * How strict blocking is for one app (STATE.md section 11). All of it is adjustable so different setups can be tried.
 */
@Serializable
data class BlockProfile(
    /** Free minutes when the app is opened by tapping one of its notifications. 0 = off. */
    val replyWindowMin: Int = 5,
    /** Quick-open (Quick Settings tile) for this app. */
    val quickOpen: Boolean = false,
    val quickOpenMin: Int = 5,
    val quickOpenPerDay: Int = 6,
    /** While in a free window, typing keeps it open. */
    val extendWhileTyping: Boolean = true,
    /** Wait before banked credits unlock it (kills the urge to scroll). */
    val creditWaitSec: Int = 60,
    /** Instant emergency unlocks per day. */
    val emergencyPerDay: Int = 3,
    /** After those run out: more, but only by typing the emergency phrase. 0 = none. */
    val extraEmergencyPerDay: Int = 5,
    /** "What are you doing?": open it by saying what for; asks again every [purposeCheckMin]. */
    val askPurpose: Boolean = false,
    val purposeCheckMin: Int = 5,
    /** Most purpose minutes per day for this app; then only credits / emergency. */
    val purposeDailyMin: Int = 60,
)

@Serializable
data class PurposeEntry(val at: Long, val pkg: String, val what: String)

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
    /** pkg -> quick-open times (last 2 days). */
    val quickOpens: Map<String, List<Long>> = emptyMap(),
    /** pkg -> end of the current free window (reply / quick-open / still-texting), so the block screen can offer "still texting?". */
    val freeWindowUntil: Map<String, Long> = emptyMap(),
    /** pkg -> when its newest message notification arrived, and the last one already used for a reply window. */
    val lastMessageAt: Map<String, Long> = emptyMap(),
    val messageWindowUsed: Map<String, Long> = emptyMap(),
    /** "What are you doing?" answers (last 2 days). */
    val purposeLog: List<PurposeEntry> = emptyList(),
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
    /** When routine tracking started. Items already past due at that moment don't nag (no burst on install). */
    val routineSince: Long? = null,
    /** Phone calendar id the app writes its plan into ("Not a Fraud" calendar created in Google Calendar). */
    val outputCalendarId: Long? = null,
    /** Phone calendar ids never read as events (old routine calendars, holidays...). */
    val excludedCalendarIds: Set<Long> = emptySet(),
    /** Phone calendar id -> how its entries are used. Missing = guessed (Canvas-like names = DEADLINES, else EVENTS). */
    val calendarModes: Map<Long, CalMode> = emptyMap(),
    /** Events-calendar entries with "exam/test/quiz" in the title get study tasks (off: use a Tests calendar instead). */
    val autoExamTasks: Boolean = false,
    /** Tests calendar: study minutes per test, and how many days before it studying may start. */
    val studyMinPerTest: Int = 360,
    val studyLeadDays: Int = 10,
    /** Play a short alarm-stream sound with bedtime nudges (gets through Do Not Disturb / Bedtime mode). */
    val bedtimeSound: Boolean = true,
    /** A stat under 4/10 adds one importance to the areas it covers. */
    val statsDriveImportance: Boolean = true,
    /** Re-plan on its own when a planned block slips by. */
    val autoReplan: Boolean = true,
    /** Accent colour key: green, amber, cyan, violet, rose, white. */
    val accent: String = "green",
    /** Things that actually recharge (walk, gym, being bored, art…), shown on recharge blocks. */
    val rechargeIdeas: List<String> = listOf("Walk outside", "Gym", "Be bored: no phone, let it process", "Art"),
    /** Pages in the bottom bar (More is always last). Ids: now, plan, tasks, routine, stats, skills, classes. */
    val tabs: List<String> = listOf("now", "plan", "routine", "tasks"),
    /** Now-page cards switched off: pick3, numbers, moved, today, upnext, idea. */
    val nowHidden: Set<String> = emptySet(),
    /** Weekly 1–5 check-in for the stats the phone can't measure (social, spirit). Off: tabled for now. */
    val checkInEnabled: Boolean = false,
    /** Homework calendars: default minutes per assignment, most per day on one, days ahead to aim for, how early it may start. */
    val homeworkMin: Int = 120,
    val homeworkDailyMaxMin: Int = 120,
    val homeworkAheadDays: Int = 2,
    val homeworkLeadDays: Int = 10,
    /** Settings lock: changes that loosen blocking wait until [lockReleaseMin] the next morning and ask again. */
    val settingsLock: Boolean = true,
    val lockReleaseMin: Int = 7 * 60,
    /** Suggest blocking apps averaging at least this many minutes a day this week. */
    val suggestBlockMin: Int = 20,
    /** Apps you said not to suggest again. */
    val notSuggest: Set<String> = emptySet(),
    /** Workout app: when the weekly minimum needs today's workout, credits can't open blocked apps until it's started. */
    val workoutPausesCredits: Boolean = true,
    /** Workout nags start at this time on a must-work-out day. */
    val workoutNagFromMin: Int = 12 * 60,
    /** Rough day (bad sleep or marked): most focus minutes the plan gives. */
    val roughFocusCapMin: Int = 240,
    /** "Still texting?" on the block screen: minutes it adds. */
    val stillTextingMin: Int = 3,
    /** Opening a blocked app within this many minutes of a new message = free reply window. */
    val messageWindowMin: Int = 10,
    /** Routine items past "angry" nag again every this many minutes. */
    val angryRepeatMin: Int = 30,
    /** Stats: weekly minutes that make Work and Mind a 10. */
    val workTargetMin: Int = 360,
    val mindTargetMin: Int = 300,
    /** A session this long on a sport skill counts as the day's workout. */
    val sportMin: Int = 20,
    /** Not a Twig: its meal / water / weigh-in / check-in reminders come through Not a Fraud's routine nags. */
    val dietNags: Boolean = true,
    val ladder: LadderSettings = LadderSettings(),
    /** Medium lock: the guard also covers the phone's Settings pages that would switch Not a Fraud off or remove it. */
    val guardSettingsPages: Boolean = true,
)

/** A Skyrim-style skill tree (Athletics, Climbing, Music, Magic...). [sport]: practice here can stand in for a workout. */
@Serializable
data class SkillTree(
    val id: String = newId(),
    val name: String,
    val sport: Boolean = false,
    val order: Int = 0,
)

/** One skill node. [parentId] = the skill it builds on. Time comes from its practice task plus manual minutes. */
@Serializable
data class Skill(
    val id: String = newId(),
    val treeId: String,
    val name: String,
    val parentId: String? = null,
    val practiceTaskId: String? = null,
    val manualMin: Int = 0,
    val notes: String = "",
    val unlockedAt: Long? = null,
)

/** Sunday check-in for what sensors can't measure. Ratings 1–5 by key (social, spirit, stress, happiness). */
@Serializable
data class CheckIn(val weekStart: String, val ratings: Map<String, Int> = emptyMap(), val at: Long = 0)

@Serializable
enum class CalMode {
    /** Fixed time to plan around. */
    EVENTS,
    /** Each entry is a task due then (Canvas). */
    DEADLINES,
    /** Each entry is a test: fixed time to plan around, plus a study task leading up to it. */
    TESTS,
    /** Each entry is homework due then: ~2 h by default, split over days, aimed to be done days early. */
    HOMEWORK,
    IGNORE,
}

/** "How's today?" choices. */
@Serializable
enum class DayMode { PUSH, RECHARGE, RESET, WRITEOFF }

@Serializable
data class Nap(val start: Long, val end: Long) {
    val minutes: Int get() = ((end - start) / 60_000).toInt()
}

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
    val naps: List<Nap> = emptyList(),
    /** First phone unlock after 4am: wake-up fallback when the watch wasn't worn. */
    val firstUnlock: Long? = null,
    /** "How's today?" answer, and when it was chosen. */
    val mode: DayMode? = null,
    val modeAt: Long? = null,
    /** Recharge: minutes of nap/walk to put first. */
    val rechargeMin: Int = 20,
    /** Task minutes in the day's first plan (for plan adherence → Discipline). */
    val plannedTaskMin: Int? = null,
    /** When the burnout prompt was shown (once a day). */
    val promptedAt: Long? = null,
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
    /** Generated tasks the user deleted, by sourceKey, so they aren't re-created. */
    val ignoredSourceKeys: Set<String> = emptySet(),
    /** Old tasks waiting for the user's Keep / Done / Delete. */
    val reviewTaskIds: Set<String> = emptySet(),
    val skillTrees: List<SkillTree> = emptyList(),
    val skills: List<Skill> = emptyList(),
    val checkIns: List<CheckIn> = emptyList(),
    val semester: Semester? = null,
    /** Last status from the workout app (null = not installed / never read). */
    val workout: com.maven.notafraud.core.workout.WorkoutStatus? = null,
    /** Last status from Not a Twig (null = not installed / never read). */
    val diet: com.maven.notafraud.core.diet.DietStatus? = null,
    /** Loosening changes waiting for the morning (settings lock). */
    val pending: List<PendingChange> = emptyList(),
    /** Until then, the phone's Settings pages for Not a Fraud open normally (granted in the morning). */
    val settingsWindowUntil: Long = 0,
    /** Draft plans for the next 6 days (today is [todayPlan]). */
    val weekPlan: List<com.maven.notafraud.core.plan.DayPlan> = emptyList(),
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


/** Tasks that can be planned: not in an archived project (an ended semester's classes, a shelved project). */
fun AppData.liveTasks(): List<Task> {
    val archived = projects.filter { it.archived }.map { it.id }.toSet()
    return tasks.filter { it.horizon != Horizon.SOMEDAY && (archived.isEmpty() || it.projectId !in archived) }
}

fun AppData.routineFor(a: AutoCheck): RoutineItem? = routine.firstOrNull { it.autoCheck == a && it.enabled } ?: routine.firstOrNull { it.autoCheck == a }
