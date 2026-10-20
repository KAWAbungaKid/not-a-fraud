package com.maven.notafraud.core.guard

/**
 * Escalating nags while a time-sink app is in front. The way out is always an action
 * (start a task, walk), never just "dismiss".
 *
 * Tone rule (STATE.md section 9): pressure, not shame. On a rough day the chain is shorter,
 * slower and gentler, and it stops after three — rest is allowed.
 */
enum class NagAction { START_TASK, WALK }

data class Nag(val level: Int, val title: String, val text: String, val action: NagAction)

object NagPolicy {

    /** Minutes after entering the app at which each nag fires. */
    fun offsetsMin(rough: Boolean): List<Int> = if (rough) listOf(10, 25, 45) else listOf(3, 8, 15, 25, 40)

    fun nag(level: Int, appLabel: String, minutesIn: Int, nextTask: String?, creed: String, rough: Boolean): Nag {
        val next = nextTask ?: "the next thing on your list"
        if (rough) {
            return when (level) {
                0 -> Nag(0, "Easy day · $appLabel ${minutesIn}m", "Rest counts. When you're ready, the smallest step is: $next.", NagAction.START_TASK)
                1 -> Nag(1, "Check-in", "Rest or avoidance? Either answer is fine. Water, a short walk, or $next.", NagAction.WALK)
                else -> Nag(2, "Last nudge today", "Phone down for 10 minutes. That's the whole ask.", NagAction.WALK)
            }
        }
        return when (level) {
            0 -> Nag(0, "$appLabel · ${minutesIn}m", "Next up: $next. Wrap it up when you're ready.", NagAction.START_TASK)
            1 -> Nag(1, "Still on $appLabel (${minutesIn}m)", "Start $next now. Ten minutes counts.", NagAction.START_TASK)
            2 -> Nag(2, "Time to move", "Phone down, walk for 5 minutes, then $next.", NagAction.WALK)
            3 -> Nag(3, "$minutesIn minutes", "This isn't the plan. One action: stand up.", NagAction.WALK)
            else -> Nag(level, creed, "Start $next. You know what to do.", NagAction.START_TASK)
        }
    }
}
