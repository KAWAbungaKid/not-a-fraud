package com.maven.diet.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

enum class NagKind { MEAL, WATER, WEIGH_IN, CHECK_IN }

/** Something overdue. [level] 1 = reminder, 2 = firm, 3 = angry (grows every Settings.nagEveryMin). */
data class Nag(val kind: NagKind, val key: String, val title: String, val text: String, val level: Int, val since: Long)

/**
 * What's overdue right now. Same spirit as Not a Fraud's routine "anger": the longer it's late, the louder.
 * Quiet before wake and after bed; meal nags only for slots inside the eating window.
 */
object Nags {
    fun due(d: DietData, now: Long, zone: ZoneId, training: Boolean): List<Nag> {
        val s = d.settings
        if (!s.nags) return emptyList()
        val z = Instant.ofEpochMilli(now).atZone(zone)
        val today = z.toLocalDate()
        val t = z.toLocalTime()
        val wake = Fasting.time(s.wake); val bed = Fasting.time(s.bed)
        if (t < wake || t >= bed) return emptyList()
        fun at(lt: LocalTime) = today.atTime(lt).atZone(zone).toInstant().toEpochMilli()
        fun level(since: Long) = (1 + Duration.ofMillis(now - since).toMinutes() / s.nagEveryMin.coerceAtLeast(10)).toInt().coerceIn(1, 3)
        val out = ArrayList<Nag>()

        // Weigh-in
        if (d.weights.none { it.date == today.toString() }) {
            val by = Fasting.time(s.weighInBy)
            if (t >= by) out += Nag(NagKind.WEIGH_IN, "weigh", "Weigh in", "Step on the scale and type it in — the trend only works with daily numbers.", level(at(by)), at(by))
        }

        // Meals
        val logged = Diary.entries(d, today).map { it.slot }.toSet()
        for (slot in s.slots) {
            val by = slot.by?.let { Fasting.time(it) } ?: continue
            if (slot.name in logged || t < by) continue
            val since = at(by)
            val txt = when (level(since)) {
                1 -> "Nothing logged for ${slot.name} yet. Eat, then log it."
                2 -> "${slot.name} is ${Duration.ofMillis(now - since).toMinutes()} min late. Food first, everything else after."
                else -> "Still no ${slot.name}. You can't recomp on nothing — eat something with protein now."
            }
            out += Nag(NagKind.MEAL, "meal:${slot.name}", "Eat: ${slot.name}", txt, level(since), since)
        }

        // Water: should be on a straight line from wake to 1 h before bed.
        val target = s.waterOz + if (training) s.waterTrainingExtraOz else 0.0
        val dayMin = Duration.between(wake, bed.minusHours(1)).toMinutes().coerceAtLeast(60)
        val frac = (Duration.between(wake, t).toMinutes().toDouble() / dayMin).coerceIn(0.0, 1.0)
        val have = Diary.waterOz(d, today)
        val behind = target * frac - have
        if (behind >= 24) {
            val lvl = when { behind >= 56 -> 3; behind >= 40 -> 2; else -> 1 }
            out += Nag(NagKind.WATER, "water", "Drink water", "${have.toInt()} of ${target.toInt()} oz — ${behind.toInt()} oz behind for this time of day.", lvl, now)
        }

        // Check-in
        if (d.checkins.none { it.date == today.toString() }) {
            val after = Fasting.time(s.checkInAfter)
            if (t >= after) out += Nag(NagKind.CHECK_IN, "checkin", "How's your head today?", "Two taps: brain fog and energy. This is the data the case study runs on.", 1, at(after))
        }
        return out
    }
}

/**
 * Bad-day helper: when today's check-in is low (clarity or energy ≤ 2), look at what the data can see and
 * suggest the basics first. Later versions rank these by what actually helped *you* (follow-up ratings).
 */
object Remedies {
    data class Suggestion(val title: String, val why: String)

    fun forToday(d: DietData, now: Long, zone: ZoneId, targets: DayTargets): List<Suggestion> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val c = d.checkins.filter { it.date == today.toString() }.maxByOrNull { it.at } ?: return emptyList()
        if (c.clarity > 2 && c.energy > 2) return emptyList()
        val out = ArrayList<Suggestion>()
        val fast = Fasting.status(d, now, zone)
        val eaten = Diary.totals(d, today)
        val hour = Instant.ofEpochMilli(now).atZone(zone).hour
        d.activity.firstOrNull { it.date == today.toString() }?.sleepH?.takeIf { it < 6.5 }?.let { h ->
            out += Suggestion("Short night (${"%.1f".format(h)} h)", "Not food's fault. Eat on time, get daylight early, caffeine before 2 pm only, and keep tonight's bedtime.")
        }
        fast.hoursSinceFood?.let { h ->
            if (h >= 5 && fast.inWindow) out += Suggestion("Eat a real meal", "${"%.0f".format(h)} h since you last ate. Protein + carbs (chicken and rice counts).")
            else if (h >= 14 && !fast.inWindow) out += Suggestion("Consider breaking the fast early", "${"%.0f".format(h)} h fasted and you feel bad — note it; that's exactly what the fasting experiment wants to know.")
        }
        val expected = targets.macros.protein * ((hour - 10).coerceIn(0, 10) / 10.0)
        if (eaten.protein < expected - 40) out += Suggestion("Get protein in", "${eaten.protein.toInt()} g so far, ~${expected.toInt()} g expected by now.")
        val water = Diary.waterOz(d, today)
        if (water < targets.waterOz * 0.4 && hour >= 12) out += Suggestion("Drink 16–24 oz of water", "Only ${water.toInt()} oz today; even mild dehydration hits focus.")
        if (hour in 8..16) out += Suggestion("10–15 min outside in daylight", "Light + a short walk is the cheapest focus reset there is.")
        out += Suggestion("Move: walk or a short workout", "A walk or the gym is what usually works for you when drained. Short mode in Not a Troglodyte counts.")
        return out
    }
}
