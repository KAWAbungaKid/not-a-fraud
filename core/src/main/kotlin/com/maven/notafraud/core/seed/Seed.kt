package com.maven.notafraud.core.seed

import com.maven.notafraud.core.model.Anchor
import com.maven.notafraud.core.model.AnchorKind
import com.maven.notafraud.core.model.AnchorMode
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.Area
import com.maven.notafraud.core.model.CalEvent
import com.maven.notafraud.core.model.Energy
import com.maven.notafraud.core.model.Goal
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.Project
import com.maven.notafraud.core.model.ProjectKind
import com.maven.notafraud.core.model.RoutineCategory
import com.maven.notafraud.core.model.RoutineItem
import com.maven.notafraud.core.model.RoutineTrigger
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskStatus
import com.maven.notafraud.core.plan.Time
import java.time.LocalDate
import java.time.ZoneId

/**
 * Starting data (STATE.md sections 7 and 10). Everything is editable in the app.
 * [data] runs on a fresh install; [Migration] upgrades an existing install.
 */
object Seed {
    const val SEM_START = "2026-08-24"
    const val SEM_END = "2026-12-11"

    /** Thanksgiving week: no classes (confirmed). */
    val THANKSGIVING = setOf("2026-11-23", "2026-11-24", "2026-11-25", "2026-11-26", "2026-11-27")

    private val RESEARCH_DATES = listOf(
        "2026-10-17", "2026-10-18", "2026-10-24", "2026-10-31",
        "2026-11-07", "2026-11-21", "2026-11-22", "2026-12-05",
    )

    private const val MON = 1; private const val TUE = 2; private const val WED = 3
    private const val THU = 4; private const val FRI = 5; private const val SAT = 6; private const val SUN = 7
    private val ALL = setOf(MON, TUE, WED, THU, FRI, SAT, SUN)
    private val WEEKDAYS = setOf(MON, TUE, WED, THU, FRI)

    private fun t(h: Int, m: Int = 0) = h * 60 + m

    // ---- Hierarchy -------------------------------------------------------------------------

    fun goals(): List<Goal> = listOf(
        Goal("g-prosthetics", "Develop prosthetics and physical therapy devices", 0),
        Goal("g-strong", "Be a mentally and physically strong person", 1),
        Goal("g-god", "Be a man of God", 2),
        Goal("g-people", "Be a good friend, boyfriend and relative", 3),
        Goal("g-community", "Give back to community and church", 4),
        Goal("g-mold", "Break the mold: efficient, financially free, able to drop anything for anyone", 5),
    )

    fun areas(): List<Area> = listOf(
        Area("a-school", "School", setOf("g-prosthetics"), 0, importance = 4),
        Area("a-research", "Research", setOf("g-prosthetics"), 1, importance = 5),
        Area("a-pt", "PT device", setOf("g-prosthetics", "g-mold"), 2),
        Area("a-career", "Career", setOf("g-prosthetics", "g-mold"), 3),
        Area("a-making", "Making and building", setOf("g-prosthetics"), 4),
        Area("a-fitness", "Fitness", setOf("g-strong"), 5),
        Area("a-health", "Health and routine", setOf("g-strong"), 6),
        Area("a-mind", "Mind", setOf("g-strong"), 7),
        Area("a-faith", "Faith", setOf("g-god"), 8),
        Area("a-church", "Church service", setOf("g-community", "g-god"), 9),
        Area("a-scouts", "Scouts STEM program", setOf("g-community"), 10),
        Area("a-people", "Relationships", setOf("g-people"), 11),
        Area("a-trading", "Trading", setOf("g-mold"), 12),
        Area("a-systems", "Efficiency and systems", setOf("g-mold", "g-strong"), 13),
    )

    /** Best-guess area for a project by its name. */
    fun areaFor(projectName: String): String? {
        val n = projectName.lowercase()
        return when {
            n.startsWith("me 3") || n.startsWith("me 4") || n.startsWith("bio sci") -> "a-school"
            "biosensor" in n || "research" in n -> "a-research"
            "career" in n || "internship" in n -> "a-career"
            "not a fraud" in n || "project max" in n -> "a-systems"
            "pt device" in n || "rehab" in n -> "a-pt"
            "scout" in n -> "a-scouts"
            "side project" in n -> "a-making"
            "trading" in n -> "a-trading"
            n == "life" -> "a-health"
            else -> null
        }
    }

    // ---- Fixed time ------------------------------------------------------------------------

    fun anchors(): List<Anchor> {
        fun cls(title: String, days: Set<Int>, s: Int, e: Int) = Anchor(
            title = title, kind = AnchorKind.CLASS, startMin = s, endMin = e, days = days,
            fromDate = SEM_START, untilDate = SEM_END, skipDates = THANKSGIVING, mode = AnchorMode.HARD,
        )
        val research = RESEARCH_DATES.flatMap { d ->
            listOf(
                Anchor(title = "Biosensor: load 1h / 4h / 8h groups", kind = AnchorKind.RESEARCH, startMin = t(8), endMin = t(8, 45), dates = setOf(d)),
                Anchor(title = "Biosensor: test 1h group", kind = AnchorKind.RESEARCH, startMin = t(9), endMin = t(9, 45), dates = setOf(d)),
                Anchor(title = "Biosensor: test 4h group", kind = AnchorKind.RESEARCH, startMin = t(12), endMin = t(12, 45), dates = setOf(d)),
                Anchor(title = "Biosensor: test 8h group", kind = AnchorKind.RESEARCH, startMin = t(16), endMin = t(16, 45), dates = setOf(d)),
            )
        }
        val craftAreas = setOf("a-pt", "a-making", "a-systems")
        return listOf(
            Anchor(title = "Trading: market open", kind = AnchorKind.MARKET, startMin = t(8, 30), endMin = t(9, 30), days = WEEKDAYS, mode = AnchorMode.HARD),
            cls("ME 3411 Dynamic Systems", setOf(TUE, THU), t(8), t(9, 15)),
            cls("ME 3521 Applied Thermo", setOf(TUE, THU), t(9, 30), t(10, 45)),
            cls("ME 3525 Heat Transfer", setOf(TUE, THU), t(11), t(12, 15)),
            cls("BIO SCI 3333 A&P", setOf(MON, WED, FRI), t(11), t(11, 50)),
            cls("ME 4840 Lab", setOf(MON, WED), t(14), t(14, 50)),
            cls("ME 4840 Lab", setOf(TUE), t(15), t(16, 50)),
            Anchor(
                title = "Craft block: build", kind = AnchorKind.CRAFT, startMin = t(14), endMin = t(17), days = setOf(FRI),
                mode = AnchorMode.CONTAINER, yieldsToHot = true, fillAreaIds = craftAreas,
            ),
            Anchor(title = "Date night", kind = AnchorKind.PROTECTED, startMin = t(18), endMin = t(23, 59), days = setOf(FRI), protected = true, mode = AnchorMode.HARD),
            Anchor(
                title = "Craft block: build", kind = AnchorKind.CRAFT, startMin = t(8), endMin = t(12), days = setOf(SAT), yieldsToDated = true,
                mode = AnchorMode.CONTAINER, yieldsToHot = true, fillAreaIds = craftAreas,
            ),
            Anchor(title = "Weekly review + pick the week", kind = AnchorKind.REVIEW, startMin = t(9), endMin = t(10), days = setOf(SUN), yieldsToDated = true, mode = AnchorMode.HABIT),
            Anchor(title = "CSWE practice", kind = AnchorKind.ROUTINE, startMin = t(10), endMin = t(11, 30), days = setOf(SUN), yieldsToDated = true, mode = AnchorMode.HABIT, yieldsToHot = true),
            Anchor(title = "Scout manual: one section", kind = AnchorKind.ROUTINE, startMin = t(13), endMin = t(13, 45), days = setOf(SUN), yieldsToDated = true, mode = AnchorMode.HABIT, yieldsToHot = true),
            Anchor(title = "Off: work stops", kind = AnchorKind.PROTECTED, startMin = t(17), endMin = t(23, 59), days = setOf(SUN), protected = true, mode = AnchorMode.HARD),
        ) + research.map { it.copy(mode = AnchorMode.HARD) }
    }

    // ---- Routine ---------------------------------------------------------------------------

    fun routine(): List<RoutineItem> {
        var o = 0
        fun r(
            id: String, title: String, cat: RoutineCategory, s: Int, due: Int, angry: Int,
            reserve: Int = 0, days: Set<Int> = ALL, trigger: RoutineTrigger = RoutineTrigger.TIME, weekly: Int? = null,
        ) = RoutineItem(id, title, cat, trigger, s, due, angry, days, reserve, true, weekly, o++, Migration.AUTO_CHECK[id])
        return listOf(
            r("r-wake", "Wake up", RoutineCategory.SLEEP, t(5, 30), t(6), t(6, 30)),
            r("r-fluids", "Drink fluids right away", RoutineCategory.BODY, t(5, 30), t(6, 15), t(7)),
            r("r-cardio", "Cardio", RoutineCategory.BODY, t(6), t(7), t(9), reserve = 45),
            r("r-shower", "Shower", RoutineCategory.HYGIENE, t(6, 45), t(7, 30), t(9), reserve = 15),
            r("r-vitamins", "Vitamins + creatine", RoutineCategory.BODY, t(6, 30), t(9), t(12)),
            r("r-meal1", "Pre-workout meal", RoutineCategory.MEAL, t(7), t(8, 30), t(10, 30), reserve = 20),
            r("r-lunch", "Lunch", RoutineCategory.MEAL, t(12, 15), t(13, 30), t(15), reserve = 30),
            r("r-workout", "Workout", RoutineCategory.BODY, t(17), t(18), t(19, 30), reserve = 60, days = setOf(MON, TUE, WED, THU, FRI, SAT), weekly = 6),
            r("r-supper", "Supper", RoutineCategory.MEAL, t(18, 15), t(19, 30), t(20, 30), reserve = 30),
            r("r-pick3", "Pick tomorrow's 3", RoutineCategory.OTHER, t(19, 30), t(21), t(21, 30)),
            r("r-teeth", "Brush teeth", RoutineCategory.HYGIENE, t(21), t(22), t(22, 15), trigger = RoutineTrigger.BEDTIME),
            r("r-bed", "In bed, phone down", RoutineCategory.SLEEP, t(21, 45), t(22), t(22, 15)),
        ).map { if (it.category == RoutineCategory.MEAL) it.copy(dietSlot = com.maven.notafraud.core.diet.DietLink.guessSlot(it.title)) else it }
    }

    // ---- Fresh install ---------------------------------------------------------------------

    fun data(now: Long, zoneId: String = "America/Chicago"): AppData {
        val zone = ZoneId.of(zoneId)
        fun kind(k: String) = when (k) {
            "school" -> ProjectKind.SCHOOL; "research" -> ProjectKind.RESEARCH; "admin" -> ProjectKind.ADMIN
            "life" -> ProjectKind.LIFE; else -> ProjectKind.PROJECT
        }
        val projectIds = HashMap<String, String>()
        val projects = WebAppSeed.projects.map { p ->
            Project(name = p.name, kind = kind(p.kind), areaId = areaFor(p.name)).also { projectIds[p.key] = it.id }
        } + listOf(
            Project(name = "Side projects", kind = ProjectKind.PROJECT, areaId = "a-making"),
            Project(name = "Life", kind = ProjectKind.LIFE, areaId = "a-health"),
        )
        fun iso(s: String?) = s?.let { java.time.Instant.parse(it).toEpochMilli() }
        fun prio(s: String) = when (s) { "high" -> Priority.HIGH; "low" -> Priority.LOW; else -> Priority.NORMAL }
        fun energy(s: String) = when (s) { "high" -> Energy.HIGH; "low" -> Energy.LOW; else -> Energy.MED }

        val tasks = WebAppSeed.tasks.filterNot { it.key == "t-android-hello" || it.title.startsWith("Study for") }.map { t ->
            Task(
                title = t.title, projectId = projectIds[t.project], estMin = t.estMin, dueAt = iso(t.dueIso),
                priority = prio(t.priority), energy = energy(t.energy), minBlockMin = t.minBlockMin,
                status = if (t.done && t.key != "t-ex-3525-3") TaskStatus.DONE else TaskStatus.TODO,
                doneAt = if (t.key == "t-ex-3525-3") null else iso(t.doneIso), createdAt = now,
            )
        }

        fun at(date: String, h: Int, m: Int = 0) = Time.at(LocalDate.parse(date), h * 60 + m, zone)
        val events = emptyList<CalEvent>()

        val base = AppData(
            schema = Migration.CURRENT,
            semester = com.maven.notafraud.core.model.Semester("Fall 2026", SEM_START, SEM_END, THANKSGIVING),
            goals = goals(), areas = areas().map { it.copy(stat = com.maven.notafraud.core.stats.Stats.DEFAULT_AREA_STAT[it.id]) }, projects = projects, tasks = tasks, events = events,
            anchors = anchors(), routine = routine(), seeded = true,
            settings = com.maven.notafraud.core.model.Settings(routineSince = now),
        )
        val (trees, skills) = Migration.skillTrees()
        return base.copy(tasks = base.tasks + Migration.catchUpTasks(base, now), skillTrees = trees, skills = skills)
    }
}

/** Upgrades data written by older versions. Runs on every load; no-op when current. */
object Migration {
    const val CURRENT = 9

    /** Seeded routine items that tick themselves (v0.5 replaced the hard-coded ids with RoutineItem.autoCheck). */
    val AUTO_CHECK = mapOf(
        "r-wake" to com.maven.notafraud.core.model.AutoCheck.WAKE,
        "r-workout" to com.maven.notafraud.core.model.AutoCheck.WORKOUT,
        "r-pick3" to com.maven.notafraud.core.model.AutoCheck.PICK3,
        "r-cardio" to com.maven.notafraud.core.model.AutoCheck.CARDIO,
    )

    fun apply(d: AppData, now: Long = System.currentTimeMillis()): AppData {
        var x = d
        if (x.schema < 2) x = toV2(x, now)
        if (x.schema < 3) x = toV3(x, now)
        if (x.schema < 4) x = toV4(x)
        if (x.schema < 5) x = toV5(x)
        if (x.schema < 6) x = toV6(x)
        if (x.schema < 7) x = toV7(x)
        if (x.schema < 8) x = toV8(x, now)
        if (x.schema < 9) x = toV9(x)
        return x
    }

    /**
     * v0.9: sports count as cardio (not toward the workout minimum), meals link to Not a Twig's slots by name.
     * The seeded workout item loses "climb" from its name.
     */
    private fun toV9(d: AppData): AppData {
        val hasCardio = d.routine.any { it.autoCheck == com.maven.notafraud.core.model.AutoCheck.CARDIO }
        val routine = d.routine.map { r ->
            var x = r
            if (!hasCardio && x.autoCheck == null && (x.id == "r-cardio" || x.title.trim().equals("Cardio", ignoreCase = true))) x = x.copy(autoCheck = com.maven.notafraud.core.model.AutoCheck.CARDIO)
            if (x.category == com.maven.notafraud.core.model.RoutineCategory.MEAL && x.dietSlot == null) x = x.copy(dietSlot = com.maven.notafraud.core.diet.DietLink.guessSlot(x.title))
            if (x.title == "Workout (lift, climb...)") x = x.copy(title = "Workout")
            x
        }
        return d.copy(schema = 9, routine = routine)
    }

    /**
     * v0.7: one priority per task. "Pinned hot" and an active week-long bump each become one level up (stored);
     * captured ideas become "try eventually" to-dos.
     */
    private fun toV8(d: AppData, now: Long): AppData {
        val tasks = d.tasks.map { t ->
            val lift = (if (t.hot) 1 else 0) + (if ((t.bumpUntil ?: 0) > now) 1 else 0)
            if (lift == 0) t.copy(hot = false, bumpUntil = null)
            else t.copy(importance = (com.maven.notafraud.core.plan.Importance.level(t, d, now) + lift).coerceAtMost(5), hot = false, bumpUntil = null)
        }
        val fromIdeas = d.ideas.filter { it.promotedTaskId == null }.map {
            com.maven.notafraud.core.model.Task(title = it.text, estMin = 60, createdAt = it.createdAt, horizon = com.maven.notafraud.core.model.Horizon.SOMEDAY)
        }
        return d.copy(schema = 8, tasks = tasks + fromIdeas, ideas = emptyList(), todayPlan = null, weekPlan = emptyList())
    }

    const val YOUTUBE = "com.google.android.youtube"

    /**
     * v0.6: Canvas-style Deadlines calendars become Homework (2 h default, spread over days, aimed 2 days early),
     * their existing tasks move over; YouTube asks "what are you doing?" instead of only credits.
     */
    private fun toV7(d: AppData): AppData {
        val s = d.settings
        val modes = s.calendarModes.mapValues { (_, m) -> if (m == com.maven.notafraud.core.model.CalMode.DEADLINES) com.maven.notafraud.core.model.CalMode.HOMEWORK else m }
        val tasks = d.tasks.map { t ->
            val k = t.sourceKey ?: return@map t
            if (!k.startsWith("deadline:")) return@map t
            val due = t.dueAt
            t.copy(
                sourceKey = com.maven.notafraud.core.plan.Homework.PREFIX + k.removePrefix("deadline:"),
                estMin = if (t.loggedMin == 0 && t.estMin == 60) s.homeworkMin else t.estMin,
                priority = Priority.HIGH,
                targetAt = due?.let { it - s.homeworkAheadDays * com.maven.notafraud.core.plan.Time.DAY },
                dailyCapMin = s.homeworkDailyMaxMin,
                maxBlockMin = t.maxBlockMin ?: 90,
                notBefore = due?.let { it - s.homeworkLeadDays * com.maven.notafraud.core.plan.Time.DAY } ?: t.notBefore,
            )
        }
        val rules = if (YOUTUBE in d.rules.blocked && YOUTUBE !in d.rules.profiles)
            d.rules.copy(profiles = d.rules.profiles + (YOUTUBE to d.rules.defaultProfile.copy(askPurpose = true, replyWindowMin = 0))) else d.rules
        return d.copy(schema = 7, settings = s.copy(calendarModes = modes), tasks = tasks, rules = rules, todayPlan = null, weekPlan = emptyList())
    }

    /**
     * v0.5: less hard-coded. Routine auto-checks and area stats become fields; the fall semester becomes a Semester;
     * a manual low/high priority folds into importance (high stays only as "firm deadline" on tasks with a due date).
     */
    private fun toV6(d: AppData): AppData {
        val routine = d.routine.map { r -> if (r.autoCheck == null) r.copy(autoCheck = AUTO_CHECK[r.id]) else r }
        val areas = d.areas.map { a -> if (a.stat == null) a.copy(stat = com.maven.notafraud.core.stats.Stats.DEFAULT_AREA_STAT[a.id]) else a }
        val hasClasses = d.anchors.any { it.kind == com.maven.notafraud.core.model.AnchorKind.CLASS }
        val semester = d.semester ?: if (hasClasses) com.maven.notafraud.core.model.Semester("Fall 2026", Seed.SEM_START, Seed.SEM_END, Seed.THANKSGIVING) else null
        val areaImp = areas.associate { it.id to it.importance }
        val tasks = d.tasks.map { t ->
            if (t.sourceKey != null || t.importance != null) return@map if (t.sourceKey == null && t.dueAt == null && t.priority != Priority.NORMAL) t.copy(priority = Priority.NORMAL) else t
            val base = d.projects.firstOrNull { it.id == t.projectId }?.areaId?.let { areaImp[it] } ?: 3
            when {
                t.priority == Priority.LOW -> t.copy(importance = (base - 1).coerceAtLeast(1), priority = Priority.NORMAL)
                t.priority == Priority.HIGH && t.dueAt == null -> t.copy(importance = (base + 1).coerceAtMost(5), priority = Priority.NORMAL)
                else -> t
            }
        }
        return d.copy(schema = 6, routine = routine, areas = areas, semester = semester, tasks = tasks, todayPlan = null, weekPlan = emptyList())
    }

    /** Starter skill trees from Maven's examples (all editable): athletics and climbing count as sport. */
    fun skillTrees(): Pair<List<com.maven.notafraud.core.model.SkillTree>, List<com.maven.notafraud.core.model.Skill>> {
        val ath = com.maven.notafraud.core.model.SkillTree(id = "st-athletics", name = "Athletics", sport = true, order = 0)
        val climb = com.maven.notafraud.core.model.SkillTree(id = "st-climbing", name = "Climbing", sport = true, order = 1)
        val music = com.maven.notafraud.core.model.SkillTree(id = "st-music", name = "Music", order = 2)
        val magic = com.maven.notafraud.core.model.SkillTree(id = "st-magic", name = "Magic", order = 3)
        val skills = listOf(
            com.maven.notafraud.core.model.Skill(id = "sk-backflip", treeId = ath.id, name = "Backflip"),
            com.maven.notafraud.core.model.Skill(id = "sk-flair", treeId = ath.id, name = "Flair (breaking)"),
            com.maven.notafraud.core.model.Skill(id = "sk-boulder", treeId = climb.id, name = "Bouldering"),
            com.maven.notafraud.core.model.Skill(id = "sk-piano", treeId = music.id, name = "Piano"),
            com.maven.notafraud.core.model.Skill(id = "sk-cards", treeId = magic.id, name = "Card tricks"),
        )
        return listOf(ath, climb, music, magic) to skills
    }

    private fun toV5(d: AppData): AppData {
        val (trees, skills) = skillTrees()
        return d.copy(schema = 5, skillTrees = d.skillTrees.ifEmpty { trees }, skills = d.skills.ifEmpty { skills })
    }

    /**
     * v0.3 -> v0.4: tests come from a Tests calendar now. Drop the hard-coded exam study tasks
     * (imported from the web app, or auto-made from exam-looking events) that haven't been worked on,
     * and the seeded ME 3411 final event, so nothing doubles up with the calendar.
     */
    private fun toV4(d: AppData): AppData {
        val webStudy = WebAppSeed.tasks.filter { it.title.startsWith("Study for") }.map { it.title }.toSet()
        val drop = d.tasks.filter { t ->
            t.status == TaskStatus.TODO && t.loggedMin == 0 &&
                ((t.sourceKey == null && t.title in webStudy) || t.sourceKey?.startsWith("exam:") == true)
        }.map { it.id }.toSet()
        return d.copy(
            schema = 4,
            tasks = d.tasks.filterNot { it.id in drop },
            reviewTaskIds = d.reviewTaskIds - drop,
            events = d.events.filterNot { it.source == "seed" },
            settings = d.settings.copy(autoExamTasks = false),
            todayPlan = null,
            weekPlan = emptyList(),
        )
    }

    /** Catch-up practice per class: time-in each week toward getting back on top of it. */
    fun catchUpTasks(d: AppData, now: Long): List<Task> =
        d.projects.filter { !it.archived && it.kind == ProjectKind.SCHOOL }
            .filter { p -> d.tasks.none { it.sourceKey == "catchup:${p.id}" } && "catchup:${p.id}" !in d.ignoredSourceKeys }
            .map { p ->
                Task(
                    title = "Catch up: ${p.name}",
                    projectId = p.id,
                    kind = com.maven.notafraud.core.model.TaskKind.PRACTICE,
                    estMin = 180,
                    weeklyTargetMin = 180,
                    minBlockMin = 30,
                    maxBlockMin = 90,
                    energy = Energy.HIGH,
                    createdAt = now,
                    sourceKey = "catchup:${p.id}",
                )
            }

    /**
     * v0.2 -> v0.3: research top importance and its open tasks pinned hot, school importance 4,
     * a catch-up practice item per class, old web-app tasks queued for review.
     */
    private fun toV3(d: AppData, now: Long): AppData {
        val areas = d.areas.map {
            when (it.id) {
                "a-research" -> it.copy(importance = 5)
                "a-school" -> it.copy(importance = 4)
                else -> it
            }
        }
        val researchProjects = d.projects.filter { it.areaId == "a-research" }.map { it.id }.toSet()
        val tasks = d.tasks.map { if (it.projectId in researchProjects && it.status == TaskStatus.TODO) it.copy(hot = true) else it }
        val webTitles = WebAppSeed.tasks.map { it.title }.toSet()
        val review = tasks.filter { it.status == TaskStatus.TODO && it.sourceKey == null && it.title in webTitles }.map { it.id }.toSet()
        val x = d.copy(areas = areas, tasks = tasks)
        return x.copy(
            schema = 3,
            tasks = x.tasks + catchUpTasks(x, now),
            reviewTaskIds = d.reviewTaskIds + review,
            todayPlan = null,
            weekPlan = emptyList(),
        )
    }

    /**
     * v0.1 -> v0.2: adds goals and areas (projects linked by name), moves meals / cardio / lifting
     * out of the weekly blocks into the routine, re-seeds the weekly blocks with modes
     * (trading every weekday, craft blocks as containers), fixes the Heat Transfer final mis-tap.
     */
    private fun toV2(d: AppData, now: Long): AppData {
        val goals = d.goals.ifEmpty { Seed.goals() }
        val areas = d.areas.ifEmpty { Seed.areas() }
        val projects = d.projects.map { p -> if (p.areaId == null) p.copy(areaId = Seed.areaFor(p.name)) else p }
        val tasks = d.tasks.map { t ->
            when {
                t.title.startsWith("Study for Heat Transfer Final") && t.status == TaskStatus.DONE && t.loggedMin == 0 ->
                    t.copy(status = TaskStatus.TODO, doneAt = null)
                t.title.startsWith("Install Not a Fraud") && t.status == TaskStatus.TODO ->
                    t.copy(status = TaskStatus.DONE, doneAt = now)
                else -> t
            }
        }
        return d.copy(
            schema = 2,
            goals = goals,
            areas = areas,
            projects = projects,
            tasks = tasks,
            anchors = Seed.anchors(),
            routine = d.routine.ifEmpty { Seed.routine() },
            settings = d.settings.copy(dayStartMin = 7 * 60, dayEndMin = 21 * 60 + 30, routineSince = now),
            todayPlan = null,
        )
    }
}
