package com.maven.diet.core

import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Logging in a hurry (DIET.md § 6): Not a Fraud's unlock check-in asks "what did you eat?" and sends either "the
 * usual" (copied from the last day you logged that slot) or a few words kept as a quick meal you fill in later.
 * Either way the time is when you actually ate, so fasting stays right.
 */
object Quick {
    data class Usual(val from: LocalDate, val label: String)

    /** The most recent day (in the last 21) with food logged in [slot] before [today]. */
    fun usual(d: DietData, slot: String, today: LocalDate): Usual? {
        for (i in 1..21) {
            val day = today.minusDays(i.toLong())
            val es = d.log.filter { it.date == day.toString() && it.slot.equals(slot, ignoreCase = true) }
            if (es.isEmpty()) continue
            val kcal = es.sumOf { Diary.macrosOf(d, it).kcal }.roundToInt()
            val names = es.mapNotNull { d.food(it.foodId)?.name }.distinct()
            val label = names.take(2).joinToString(" + ") + (if (names.size > 2) " +${names.size - 2}" else "") + " · $kcal kcal"
            return Usual(day, label)
        }
        return null
    }

    fun slotName(d: DietData, slot: String): String = d.settings.slots.firstOrNull { it.name.equals(slot, ignoreCase = true) }?.name ?: slot

    /** "The usual": copy the last logged day of that slot, at [at]. False-y (unchanged) when there's nothing to copy. */
    fun logUsual(d: DietData, slot: String, date: LocalDate, at: Long): DietData {
        val name = slotName(d, slot)
        val u = usual(d, name, date) ?: return d
        val items = d.log.filter { it.date == u.from.toString() && it.slot == name }
        return items.fold(d) { acc, e -> Actions.logFood(acc, date, name, e.foodId, e.servings, at, e.amount, e.unit) }
    }

    fun add(d: DietData, slot: String, text: String, date: LocalDate, at: Long): DietData =
        d.copy(quick = d.quick + QuickMeal(Actions.newId(), date.toString(), slotName(d, slot), at, text.trim().ifBlank { "meal" }))

    fun resolve(d: DietData, id: String) = d.copy(quick = d.quick.map { if (it.id == id) it.copy(resolved = true) else it })

    fun remove(d: DietData, id: String) = d.copy(quick = d.quick.filter { it.id != id })

    /** Adding food to a slot that has a pending quick meal resolves it (and uses its time). */
    fun pendingFor(d: DietData, date: LocalDate, slot: String): QuickMeal? =
        d.quick.firstOrNull { it.date == date.toString() && it.slot == slot && !it.resolved }
}

/** Which foods made up a macro today: "protein: 30 % chicken, 5 % chocolate milk". */
object Breakdown {
    enum class Macro(val label: String) { KCAL("Calories"), PROTEIN("Protein"), CARBS("Carbs"), FAT("Fat") }

    fun of(m: Macros, which: Macro) = when (which) { Macro.KCAL -> m.kcal; Macro.PROTEIN -> m.protein; Macro.CARBS -> m.carbs; Macro.FAT -> m.fat }

    data class Part(val name: String, val amount: Double, val pct: Int)

    fun contributors(d: DietData, date: LocalDate, which: Macro): List<Part> {
        val byFood = HashMap<String, Double>()
        for (e in Diary.entries(d, date)) {
            val name = d.food(e.foodId)?.name ?: "(deleted food)"
            byFood[name] = (byFood[name] ?: 0.0) + of(Diary.macrosOf(d, e), which)
        }
        val total = byFood.values.sum()
        if (total <= 0) return emptyList()
        return byFood.entries.filter { it.value > 0 }.sortedByDescending { it.value }
            .map { Part(it.key, it.value, (it.value * 100 / total).roundToInt()) }
    }
}

/**
 * End of the day and a macro's short: foods you have that are mostly that macro, with how much fills the gap
 * without blowing the others. "Carbs 80 g short: 1.5 cups rice (68 g) · 2 bananas (54 g)".
 */
object FillGap {
    data class Pick(val food: Food, val servings: Double, val gives: Macros, val why: String)

    fun suggest(d: DietData, date: LocalDate, target: Macros, which: Breakdown.Macro, limit: Int = 4): List<Pick> {
        if (which == Breakdown.Macro.KCAL) return emptyList()
        val eaten = Diary.totals(d, date)
        val gap = Breakdown.of(target, which) - Breakdown.of(eaten, which)
        if (gap <= 5) return emptyList()
        val roomKcal = (target.kcal - eaten.kcal).coerceAtLeast(gap * if (which == Breakdown.Macro.FAT) 9 else 4)
        // Foods you've actually eaten in the last 60 days first, then everything else.
        val since = date.minusDays(60).toString()
        val recent = d.log.filter { it.date >= since }.groupingBy { it.foodId }.eachCount()
        return d.foods.filter { !it.archived && it.kcal > 0 }.mapNotNull { f ->
            val per = Breakdown.of(Macros(f.kcal, f.protein, f.carbs, f.fat), which)
            if (per <= 0) return@mapNotNull null
            val share = per * (if (which == Breakdown.Macro.FAT) 9 else 4) / f.kcal
            if (share < 0.55) return@mapNotNull null
            var servings = gap / per
            servings = (Math.round(servings * 2) / 2.0).coerceIn(0.5, 4.0)
            val gives = Macros(f.kcal, f.protein, f.carbs, f.fat) * servings
            if (gives.kcal > roomKcal * 1.15) servings = (Math.floor(roomKcal / f.kcal * 2) / 2.0).coerceAtLeast(0.5)
            val g = Macros(f.kcal, f.protein, f.carbs, f.fat) * servings
            val score = share * 2 + (recent[f.id] ?: 0).coerceAtMost(10) * 0.1
            Pick(f, servings, g, "${(share * 100).roundToInt()}% ${which.label.lowercase()}") to score
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
