package com.maven.notafraud.core.score

import com.maven.notafraud.core.model.Energy
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.Task
import kotlin.math.max
import kotlin.math.min

/**
 * Task scoring engine, ported verbatim from the web app (STATE.md section 2).
 *
 *   est    = max(10, estMin);  left = max(10, est - loggedMin)
 *   slack  = hoursUntilDue - left/60
 *   urgency = slack > 0 ? 100/(1 + slack/12) : 100/(1 + (-slack)/36)   (no due date -> 8)
 *   prio   = high 1.45 | normal 1.0 | low 0.65
 *   fit    = freeWindow < minBlockMin ? 0.2 : freeWindow >= left ? 1.15 : 1.0
 *   energy = exact match 1.15 | task high & user low 0.45 | task low & user high 0.85 | else 1.0
 *   neglect = min(18, daysSinceLastWorked * 3)
 *   score  = (urgency * prio + neglect) * fit * energy
 *
 * The post-deadline decay matters: without it an overdue low-priority chore outranks
 * a graded exam due in 8 hours.
 */
object Scorer {

    data class Breakdown(
        val score: Double,
        val urgency: Double,
        val prio: Double,
        val fit: Double,
        val energy: Double,
        val neglect: Double,
        val leftMin: Int,
    )

    fun minutesLeft(task: Task): Int {
        if (task.steps.isNotEmpty()) return task.steps.filter { it.doneAt == null }.sumOf { it.min }
        val est = max(10, task.estMin)
        return max(10, est - task.loggedMin)
    }

    fun score(task: Task, now: Long, freeWindowMin: Int, userEnergy: Energy): Double =
        breakdown(task, now, freeWindowMin, userEnergy).score

    fun breakdown(task: Task, now: Long, freeWindowMin: Int, userEnergy: Energy): Breakdown {
        val left = minutesLeft(task)
        fun urg(due: Long): Double {
            val hoursUntilDue = (due - now) / 3_600_000.0
            val slack = hoursUntilDue - left / 60.0
            return if (slack > 0) 100.0 / (1 + slack / 12.0) else 100.0 / (1 + (-slack) / 36.0)
        }
        // A "done by" target (homework: days early) pulls work forward; the real due date still counts.
        val urgency = listOfNotNull(task.dueAt?.let(::urg), task.targetAt?.let(::urg)).maxOrNull() ?: 8.0

        val prio = when (task.priority) {
            Priority.HIGH -> 1.45
            Priority.NORMAL -> 1.0
            Priority.LOW -> 0.65
        }

        val fit = when {
            freeWindowMin < task.minBlockMin -> 0.2
            freeWindowMin >= left -> 1.15
            else -> 1.0
        }

        val energy = when {
            task.energy == userEnergy -> 1.15
            task.energy == Energy.HIGH && userEnergy == Energy.LOW -> 0.45
            task.energy == Energy.LOW && userEnergy == Energy.HIGH -> 0.85
            else -> 1.0
        }

        val lastTouched = task.lastWorkedAt ?: task.createdAt
        val daysSince = max(0.0, (now - lastTouched) / 86_400_000.0)
        val neglect = min(18.0, daysSince * 3)

        val score = (urgency * prio + neglect) * fit * energy
        return Breakdown(score, urgency, prio, fit, energy, neglect, left)
    }

    /** Open tasks ranked best-first. */
    fun rank(tasks: List<Task>, now: Long, freeWindowMin: Int, userEnergy: Energy): List<Pair<Task, Double>> =
        tasks.filter { it.status == com.maven.notafraud.core.model.TaskStatus.TODO && it.kind != com.maven.notafraud.core.model.TaskKind.WAITING }
            .filter { it.notBefore == null || it.notBefore <= now }
            .map { it to score(it, now, freeWindowMin, userEnergy) }
            .sortedByDescending { it.second }
}
