package com.maven.workout.core

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The spreadsheet's RPE chart (REFERENCE!A:D): % of 1RM for reps 1–12 at RPE 6–10.
 * Every value in that chart depends only on reps + (10 − RPE) ("effective reps"): 1 @ 9 = 2 @ 10 = 95.5%.
 * So it is stored as one row and interpolated for half RPEs (8.5). Beyond the chart it keeps the
 * chart's last slope, floored at 30%.
 */
object Rpe {
    /** PCT[n-1] = % of 1RM for n effective reps (n = reps + 10 − rpe), n = 1..16. */
    private val PCT = doubleArrayOf(
        1.000, 0.955, 0.922, 0.892, 0.863, 0.837, 0.811, 0.786,
        0.762, 0.739, 0.707, 0.680, 0.653, 0.626, 0.599, 0.574,
    )

    fun pct(reps: Int, rpe: Double = 10.0): Double {
        val n = reps + (10.0 - rpe.coerceIn(1.0, 10.0))
        if (n <= 1.0) return 1.0
        val last = PCT.size.toDouble()
        if (n >= last) {
            val slope = PCT[PCT.size - 1] - PCT[PCT.size - 2]
            return (PCT.last() + slope * (n - last)).coerceAtLeast(0.30)
        }
        val i = floor(n).toInt()
        val f = n - i
        return PCT[i - 1] + (PCT[i] - PCT[i - 1]) * f
    }

    /** Estimated 1RM from a set. Null when the set can't say much (no weight, or a very long set). */
    fun e1rm(weight: Double, reps: Int, rpe: Double? = null): Double? {
        if (weight <= 0 || reps < 1 || reps > 20) return null
        return weight / pct(reps, rpe ?: 10.0)
    }

    /** Weight for reps @ rpe from a 1RM, before rounding. */
    fun weightFor(oneRm: Double, reps: Int, rpe: Double?): Double = oneRm * pct(reps, rpe ?: 10.0)

    /** The sheet's MROUND: nearest multiple of [step]. */
    fun round(weight: Double, step: Double): Double {
        if (step <= 0) return weight
        return (weight / step).roundToLong() * step
    }

    fun fmt(w: Double?): String = when {
        w == null -> ""
        abs(w - w.roundToLong()) < 1e-6 -> w.roundToLong().toString()
        else -> "%.1f".format(w)
    }
}

/** Text form of a [Step], used by the progression editor: "3x4 @7 top3", "3xF", "3x45s @8", "3x1 @10 top1 test". */
object StepFormat {
    fun format(s: Step): String = buildString {
        append(s.sets).append('x')
        when {
            s.seconds != null -> append(s.seconds).append('s')
            s.reps != null -> append(s.reps)
            else -> append('F')
        }
        s.rpe?.let { append(" @").append(Rpe.fmt(it)) }
        s.topReps?.let { append(" top").append(it) }
        if (s.test) append(" test")
    }

    private val HEAD = Regex("""^\s*(\d+)\s*[x×]\s*(\d+\s*s|\d+|f|max|amrap)""", RegexOption.IGNORE_CASE)
    private val RPE = Regex("""@\s*(\d+(?:\.\d+)?)""")
    private val TOP = Regex("""top\s*(\d+)""", RegexOption.IGNORE_CASE)

    fun parse(text: String): Step? {
        val m = HEAD.find(text) ?: return null
        val sets = m.groupValues[1].toInt().coerceIn(1, 20)
        val v = m.groupValues[2].lowercase().replace(" ", "")
        val (reps, secs) = when {
            v.endsWith("s") -> null to v.dropLast(1).toInt()
            v == "f" || v == "max" || v == "amrap" -> null to null
            else -> v.toInt() to null
        }
        val rpe = RPE.find(text)?.groupValues?.get(1)?.toDouble()?.coerceIn(1.0, 10.0)
        val top = TOP.find(text)?.groupValues?.get(1)?.toInt()
        val test = text.contains("test", ignoreCase = true)
        return Step(sets = sets, reps = reps, seconds = secs, rpe = rpe, topReps = top, test = test)
    }
}

/** "245x4", "245 x 4 @9", "75x8@8.5" → weight, reps, rpe. The sheet's Maxes!C column. */
object SetText {
    private val RX = Regex("""^\s*(\d+(?:\.\d+)?)\s*[x×*]\s*(\d+)\s*(?:@\s*(\d+(?:\.\d+)?))?\s*$""", RegexOption.IGNORE_CASE)
    fun parse(text: String): Triple<Double, Int, Double>? {
        val m = RX.find(text) ?: return null
        return Triple(m.groupValues[1].toDouble(), m.groupValues[2].toInt(), m.groupValues[3].toDoubleOrNull() ?: 10.0)
    }
}
