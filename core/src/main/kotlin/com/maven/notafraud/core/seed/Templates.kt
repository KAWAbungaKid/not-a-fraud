package com.maven.notafraud.core.seed

import com.maven.notafraud.core.model.Anchor
import com.maven.notafraud.core.model.AnchorKind
import com.maven.notafraud.core.model.AnchorMode
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.Area
import com.maven.notafraud.core.model.AutoCheck
import com.maven.notafraud.core.model.Goal
import com.maven.notafraud.core.model.Module
import com.maven.notafraud.core.model.Project
import com.maven.notafraud.core.model.ProjectKind
import com.maven.notafraud.core.model.RoutineCategory
import com.maven.notafraud.core.model.RoutineItem
import com.maven.notafraud.core.model.RoutineTrigger
import com.maven.notafraud.core.model.Settings

/**
 * A fresh install for anyone (STATE.md § 25): nothing about Maven. [fresh] is the empty start shown behind
 * onboarding; [apply] turns the onboarding answers into goals, areas, a starter routine and weekly blocks.
 * Maven's own setup ([Seed]) is no longer used for new installs.
 */
object Templates {
    /** A goal people can pick, the area it creates and the stat that area feeds. */
    data class GoalChoice(val id: String, val goal: String, val area: String, val stat: String?, val suggests: Set<Module>)

    val GOALS = listOf(
        GoalChoice("school", "Do well in school", "School", "mind", setOf(Module.PLANNER, Module.BLOCKING)),
        GoalChoice("career", "Grow my career", "Career", "work", setOf(Module.PLANNER)),
        GoalChoice("focus", "Spend less time on my phone", "Focus", "discipline", setOf(Module.BLOCKING)),
        GoalChoice("fitness", "Get fit and strong", "Fitness", "body", setOf(Module.ROUTINE, Module.STATS)),
        GoalChoice("health", "Sleep, eat and live healthier", "Health", "body", setOf(Module.ROUTINE)),
        GoalChoice("faith", "Grow in my faith", "Faith", "spirit", setOf(Module.ROUTINE)),
        GoalChoice("people", "Be there for family and friends", "Relationships", "social", setOf(Module.ROUTINE)),
        GoalChoice("projects", "Build my own projects", "Projects", "work", setOf(Module.PLANNER, Module.SKILLS)),
        GoalChoice("skills", "Learn new skills", "Learning", "mind", setOf(Module.SKILLS)),
        GoalChoice("money", "Get my money right", "Money", "discipline", setOf(Module.PLANNER)),
    )

    enum class Schedule(val label: String) { SCHOOL("School"), WORK("Work"), BOTH("School and work"), NEITHER("Neither / it varies") }

    /** A starter habit: times are relative to wake ([fromWake] = true) or bedtime (minutes, can be negative). */
    data class Starter(
        val id: String, val title: String, val category: RoutineCategory, val offset: Int, val fromWake: Boolean,
        val windowMin: Int = 30, val angryAfter: Int = 30, val reserveMin: Int = 0, val autoCheck: AutoCheck? = null,
        val trigger: RoutineTrigger = RoutineTrigger.TIME, val weekly: Int? = null, val forGoals: Set<String> = emptySet(),
    )

    val STARTERS = listOf(
        Starter("wake", "Wake up", RoutineCategory.SLEEP, 0, true, autoCheck = AutoCheck.WAKE, angryAfter = 30),
        Starter("water", "Drink water", RoutineCategory.BODY, 0, true, windowMin = 45),
        Starter("breakfast", "Breakfast", RoutineCategory.MEAL, 30, true, windowMin = 90, angryAfter = 90, reserveMin = 20, forGoals = setOf("health")),
        Starter("shower", "Shower", RoutineCategory.HYGIENE, 60, true, windowMin = 60, angryAfter = 90, reserveMin = 15),
        Starter("lunch", "Lunch", RoutineCategory.MEAL, 6 * 60, true, windowMin = 90, angryAfter = 90, reserveMin = 30, forGoals = setOf("health")),
        Starter("workout", "Workout", RoutineCategory.BODY, 11 * 60, true, windowMin = 60, angryAfter = 90, reserveMin = 60, autoCheck = AutoCheck.WORKOUT, weekly = 3, forGoals = setOf("fitness")),
        Starter("prayer", "Prayer / Bible", RoutineCategory.OTHER, 15, true, windowMin = 60, angryAfter = 120, forGoals = setOf("faith")),
        Starter("supper", "Supper", RoutineCategory.MEAL, -3 * 60 - 30, false, windowMin = 90, angryAfter = 60, reserveMin = 30, forGoals = setOf("health")),
        Starter("call", "Text or call someone", RoutineCategory.OTHER, -3 * 60, false, windowMin = 120, angryAfter = 60, forGoals = setOf("people")),
        Starter("pick3", "Pick tomorrow's 3", RoutineCategory.OTHER, -90, false, windowMin = 60, angryAfter = 30, autoCheck = AutoCheck.PICK3),
        Starter("teeth", "Brush teeth", RoutineCategory.HYGIENE, -30, false, windowMin = 30, angryAfter = 15, trigger = RoutineTrigger.BEDTIME),
        Starter("bed", "In bed, phone down", RoutineCategory.SLEEP, 0, false, windowMin = 15, angryAfter = 15),
    )

    /** Starters ticked by default for these goals. */
    fun suggestedStarters(goals: Set<String>): Set<String> =
        STARTERS.filter { it.forGoals.isEmpty() && it.id in setOf("wake", "water", "teeth", "bed") || it.forGoals.any { g -> g in goals } }.map { it.id }.toSet()

    fun suggestedModules(goals: Set<String>): Set<Module> =
        (GOALS.filter { it.id in goals }.flatMap { it.suggests } + Module.PLANNER + Module.ROUTINE).toSet()

    data class Answers(
        val name: String = "",
        val goals: Map<String, Int> = emptyMap(),
        val customGoals: List<String> = emptyList(),
        val modules: Set<Module> = Module.entries.toSet(),
        val wakeMin: Int = 6 * 60 + 30,
        val bedMin: Int = 22 * 60 + 30,
        val schedule: Schedule = Schedule.NEITHER,
        /** Work hours on weekdays when [schedule] has work. */
        val workStartMin: Int = 9 * 60,
        val workEndMin: Int = 17 * 60,
        val workDays: Set<Int> = setOf(1, 2, 3, 4, 5),
        val starters: Set<String> = emptySet(),
        val workoutsPerWeek: Int = 3,
        val restDay: Int? = 7,
    )

    /** What a new install starts from, before onboarding fills it in. */
    fun fresh(now: Long, zoneId: String): AppData = AppData(
        schema = Migration.CURRENT,
        settings = Settings(zoneId = zoneId, onboarded = false, routineSince = now, tabs = listOf("now", "plan", "routine", "tasks")),
        seeded = true,
    )

    private fun clampDay(m: Int) = ((m % 1440) + 1440) % 1440

    fun apply(d: AppData, a: Answers, now: Long): AppData {
        val goals = ArrayList<Goal>()
        val areas = ArrayList<Area>()
        var o = 0
        for (g in GOALS.filter { it.id in a.goals }) {
            val goal = Goal(name = g.goal, order = o)
            goals += goal
            areas += Area(name = g.area, goalIds = setOf(goal.id), order = o, importance = (a.goals[g.id] ?: 3).coerceIn(1, 5), stat = g.stat)
            o++
        }
        for (c in a.customGoals.map { it.trim() }.filter { it.isNotEmpty() }) {
            val goal = Goal(name = c, order = o)
            goals += goal
            areas += Area(name = c.take(28), goalIds = setOf(goal.id), order = o, importance = 3)
            o++
        }
        val life = Area(name = "Life", order = o, importance = 3, stat = "hygiene")
        areas += life
        val projects = areas.map { ar ->
            Project(name = if (ar.id == life.id) "Life" else ar.name, kind = when {
                ar.id == life.id -> ProjectKind.LIFE
                ar.name == "School" -> ProjectKind.SCHOOL
                else -> ProjectKind.PROJECT
            }, areaId = ar.id)
        }

        var order = 0
        val routine = STARTERS.filter { it.id in a.starters }.map { s ->
            val due = clampDay(if (s.fromWake) a.wakeMin + s.offset + s.windowMin else a.bedMin + s.offset)
            val start = clampDay(due - s.windowMin)
            RoutineItem(
                id = "r-${s.id}", title = s.title, category = s.category, trigger = s.trigger,
                startMin = start, dueMin = due, angryMin = clampDay(due + s.angryAfter).coerceAtLeast(due),
                days = if (s.id == "workout" && a.restDay != null) (1..7).toSet() - a.restDay else (1..7).toSet(),
                reserveMin = s.reserveMin, weeklyTarget = if (s.id == "workout") a.workoutsPerWeek else s.weekly,
                order = order++, autoCheck = s.autoCheck,
                dietSlot = if (s.category == RoutineCategory.MEAL) com.maven.notafraud.core.diet.DietLink.guessSlot(s.title) else null,
            )
        }.sortedBy { it.dueMin }.mapIndexed { i, r -> r.copy(order = i) }

        val anchors = buildList {
            if (a.schedule == Schedule.WORK || a.schedule == Schedule.BOTH) add(
                Anchor(title = "Work", kind = AnchorKind.WORK, startMin = a.workStartMin, endMin = a.workEndMin, days = a.workDays, mode = AnchorMode.HARD),
            )
            add(Anchor(title = "Weekly review + plan the week", kind = AnchorKind.REVIEW, startMin = 18 * 60, endMin = 18 * 60 + 45, days = setOf(7), yieldsToDated = true, mode = AnchorMode.HABIT))
        }

        val tabs = buildList {
            add("now")
            if (Module.PLANNER in a.modules) add("plan")
            if (Module.ROUTINE in a.modules) add("routine")
            add("tasks")
            if (Module.STATS in a.modules && size < 4) add("stats")
        }
        return d.copy(
            goals = goals, areas = areas, projects = d.projects + projects, routine = routine, anchors = d.anchors + anchors,
            settings = d.settings.copy(
                onboarded = true, userName = a.name.trim(), modules = a.modules, wakeMin = a.wakeMin, bedMin = a.bedMin,
                routineSince = now, tabs = tabs,
                creed = "Do the next right thing.",
                routineNagsEnabled = Module.ROUTINE in a.modules,
                ladder = d.settings.ladder.copy(enabled = Module.BLOCKING in a.modules, bedMin = a.bedMin, wakeMin = a.wakeMin),
            ),
            rules = d.rules.copy(blockingEnabled = Module.BLOCKING in a.modules && d.rules.blockingEnabled),
        )
    }
}
