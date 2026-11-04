package com.maven.diet.core

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Eat This Much, Maven-sized (§ 5 step 4): fill the rest of a day's slots from YOUR foods, saved meals and
 * recipes (what you actually eat, chicken and rice first) so the day lands on its calories with protein covered.
 * Optionally prefers cheaper picks when foods have a price. "Shuffle" gives another plan; nothing is logged
 * until you say so. Grocery list sums a few planned days.
 */
object MealPlanner {
    sealed class Pick {
        abstract val label: String
        data class FoodPick(val foodId: String, val servings: Double, override val label: String) : Pick()
        data class MealPick(val mealId: String, val portions: Double, override val label: String) : Pick()
    }

    data class SlotPlan(val slot: String, val items: List<Pick>, val macros: Macros, val cost: Double?)
    data class DayPlan(val date: LocalDate, val slots: List<SlotPlan>, val macros: Macros, val cost: Double?, val note: String)

    private data class Cand(val pick: (Double) -> Pick, val per: Macros, val cost: Double?, val step: Double, val usual: Double)

    private fun mealMacros(d: DietData, m: SavedMeal): Macros {
        val k = 1.0 / m.servings.coerceAtLeast(0.01)
        return m.items.fold(Macros.ZERO) { acc, it -> d.food(it.foodId)?.let { f -> acc + Macros(f.kcal, f.protein, f.carbs, f.fat) * (it.servings * k) } ?: acc }
    }

    private fun mealCost(d: DietData, m: SavedMeal): Double? {
        val k = 1.0 / m.servings.coerceAtLeast(0.01)
        val parts = m.items.map { d.food(it.foodId)?.price?.times(it.servings * k) }
        return if (parts.isEmpty() || parts.any { it == null }) null else parts.sumOf { it!! }
    }

    /** What the planner may pick from: saved meals / recipes, and foods you've logged at least twice (at your usual amount). */
    private fun candidates(d: DietData, today: LocalDate): List<Cand> {
        val out = ArrayList<Cand>()
        for (m in d.meals) {
            val per = mealMacros(d, m)
            if (per.kcal < 50) continue
            out += Cand({ k -> Pick.MealPick(m.id, k, m.name) }, per, mealCost(d, m), 0.5, 1.0)
        }
        val since = today.minusDays(45).toString()
        val usage = d.log.filter { it.date >= since }.groupBy { it.foodId }
        for ((id, uses) in usage) {
            val f = d.food(id) ?: continue
            if (f.archived || uses.size < 2 || f.kcal < 20) continue
            val usual = uses.map { it.servings }.sorted()[uses.size / 2]
            out += Cand({ k -> Pick.FoodPick(f.id, k, f.name) }, Macros(f.kcal, f.protein, f.carbs, f.fat), f.price, 0.25, usual)
        }
        // Fresh install: the built-in staples, so the planner has something to work with.
        if (out.size < 4) for (f in d.foods.filter { !it.archived && it.kcal >= 60 }.take(20)) {
            if (out.none { (it.pick(1.0) as? Pick.FoodPick)?.foodId == f.id }) out += Cand({ k -> Pick.FoodPick(f.id, k, f.name) }, Macros(f.kcal, f.protein, f.carbs, f.fat), f.price, 0.25, 1.0)
        }
        return out
    }

    private fun round(x: Double, step: Double) = (max(step, (x / step).roundToInt() * step) * 100).roundToInt() / 100.0

    /**
     * Plan the slots still empty on [date]: what's already logged counts, the rest of [target] is spread over the
     * open timed slots. [seed] picks a different plan each "Shuffle". [cheap] leans on price when foods have one.
     */
    fun plan(d: DietData, date: LocalDate, target: Macros, seed: Int = 0, cheap: Boolean = false): DayPlan {
        val eaten = Diary.totals(d, date)
        val logged = Diary.entries(d, date).map { it.slot }.toSet()
        val open = d.settings.slots.filter { it.by != null && it.name !in logged }.map { it.name }
        val cands = candidates(d, date)
        if (open.isEmpty() || cands.isEmpty()) {
            return DayPlan(date, emptyList(), Macros.ZERO, null, if (cands.isEmpty()) "Log or save a few meals first: the planner only uses what you actually eat." else "Every timed slot already has food.")
        }
        val leftKcal = (target.kcal - eaten.kcal).coerceAtLeast(0.0)
        val leftProtein = (target.protein - eaten.protein).coerceAtLeast(0.0)
        val rnd = kotlin.random.Random(seed * 7919 + date.toEpochDay().toInt())
        val used = HashSet<Int>()
        val slots = ArrayList<SlotPlan>()
        var kcalLeft = leftKcal
        var protLeft = leftProtein
        for ((i, slot) in open.withIndex()) {
            val share = 1.0 / (open.size - i)
            val wantK = kcalLeft * share
            val wantP = protLeft * share
            fun score(c: Cand, k: Double): Double {
                val m = c.per * k
                val err = abs(m.kcal - wantK) / wantK.coerceAtLeast(100.0) + 1.5 * max(0.0, wantP - m.protein) / wantP.coerceAtLeast(10.0)
                return err + (if (cheap && c.cost != null) c.cost * k * 0.04 else 0.0) + (if (c.cost == null && cheap) 0.05 else 0.0)
            }
            // Main: the best few candidates scaled to the slot, one of the top 4 at random (shuffle).
            val ranked = cands.map { c ->
                val k = round((wantK / c.per.kcal.coerceAtLeast(1.0)).coerceIn(c.usual * 0.5, c.usual * 2.5), c.step)
                Triple(c, k, score(c, k))
            }.map { (c, k, sc) -> Triple(c, k, sc + if (System.identityHashCode(c) in used) 0.35 else 0.0) }.sortedBy { it.third }
            if (ranked.isEmpty()) continue
            val (main, mk, _) = ranked[rnd.nextInt(minOf(4, ranked.size))]
            val items = mutableListOf(main.pick(mk))
            var m = main.per * mk
            var cost = main.cost?.times(mk)
            // Protein short? Add the leanest protein you eat.
            if (wantP - m.protein > 15) {
                val lean = cands.filter { it !== main && it.per.protein / it.per.kcal.coerceAtLeast(1.0) > 0.08 }
                    .maxByOrNull { it.per.protein / it.per.kcal.coerceAtLeast(1.0) }
                if (lean != null) {
                    val k = round(((wantP - m.protein) / lean.per.protein.coerceAtLeast(1.0)).coerceAtMost(lean.usual * 2), lean.step)
                    items += lean.pick(k); m = m + lean.per * k
                    cost = if (cost != null && lean.cost != null) cost + lean.cost * k else null
                }
            }
            used += System.identityHashCode(main)
            kcalLeft = (kcalLeft - m.kcal).coerceAtLeast(0.0)
            protLeft = (protLeft - m.protein).coerceAtLeast(0.0)
            slots += SlotPlan(slot, items, m, cost)
        }
        val total = slots.fold(Macros.ZERO) { a, s -> a + s.macros }
        val cost = slots.map { it.cost }.takeIf { it.all { c -> c != null } && it.isNotEmpty() }?.sumOf { it!! }
        val note = "Planned ${total.kcal.roundToInt()} of ${leftKcal.roundToInt()} kcal left, ${total.protein.roundToInt()} of ${leftProtein.roundToInt()} g protein left."
        return DayPlan(date, slots, total, cost, note)
    }

    data class GroceryLine(val foodId: String, val name: String, val servings: Double, val amount: String, val cost: Double?)

    /** Everything those plans need, food by food, with an amount you can shop by. */
    fun groceries(d: DietData, plans: List<DayPlan>): List<GroceryLine> {
        val need = HashMap<String, Double>()
        for (p in plans) for (s in p.slots) for (it in s.items) when (it) {
            is Pick.FoodPick -> need[it.foodId] = (need[it.foodId] ?: 0.0) + it.servings
            is Pick.MealPick -> d.meal(it.mealId)?.let { m ->
                val k = it.portions / m.servings.coerceAtLeast(0.01)
                for (mi in m.items) need[mi.foodId] = (need[mi.foodId] ?: 0.0) + mi.servings * k
            }
        }
        return need.mapNotNull { (id, sv) ->
            val f = d.food(id) ?: return@mapNotNull null
            val amount = f.servingGrams?.let { g -> val total = g * sv; if (total >= 454) "%.1f lb".format(total / 453.6) else "${total.roundToInt()} g" }
                ?: "%.1f × %s".format(sv, f.serving)
            GroceryLine(id, f.name, sv, amount, f.price?.times(sv))
        }.sortedBy { it.name.lowercase() }
    }
}
