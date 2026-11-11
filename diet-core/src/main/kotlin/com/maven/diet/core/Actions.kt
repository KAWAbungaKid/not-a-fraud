package com.maven.diet.core

import java.time.LocalDate
import java.util.UUID

/** Every change to the data, as pure functions. */
object Actions {
    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun slug(name: String, taken: Set<String>): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "food" }
        var id = base; var n = 2
        while (id in taken) id = "$base-${n++}"
        return id
    }

    fun logFood(
        d: DietData, date: LocalDate, slot: String, foodId: String, servings: Double, at: Long,
        amount: Double? = null, unit: String? = null,
    ): DietData {
        if (servings <= 0 || d.food(foodId) == null) return d
        return d.copy(log = d.log + LogEntry(newId(), date.toString(), slot, foodId, servings, at, amount, unit))
    }

    /** Log [amount] of [unit] ("6", "oz"); converted to servings with [Units]. */
    fun logAmount(d: DietData, date: LocalDate, slot: String, foodId: String, amount: Double, unit: String, at: Long): DietData {
        val f = d.food(foodId) ?: return d
        val s = Units.toServings(f, amount, unit) ?: return d
        return logFood(d, date, slot, foodId, s, at, amount, unit)
    }

    fun editEntry(
        d: DietData, id: String, servings: Double?, slot: String? = null, amount: Double? = null, unit: String? = null,
    ): DietData = d.copy(log = d.log.mapNotNull { e ->
        if (e.id != id) e
        else if (servings != null && servings <= 0) null
        else e.copy(
            servings = servings ?: e.servings, slot = slot ?: e.slot,
            amount = if (servings != null) amount else e.amount, unit = if (servings != null) unit else e.unit,
        )
    })

    /** Move when it was eaten (fasting uses it). */
    fun setEntryAt(d: DietData, id: String, at: Long) = d.copy(log = d.log.map { if (it.id == id) it.copy(at = at) else it })

    fun removeEntry(d: DietData, id: String) = d.copy(log = d.log.filter { it.id != id })

    /** Log a saved meal / recipe: every item × (portions / recipe servings). */
    fun logMeal(d: DietData, date: LocalDate, slot: String, mealId: String, portions: Double, at: Long): DietData {
        val m = d.meal(mealId) ?: return d
        val k = portions / m.servings.coerceAtLeast(0.01)
        return m.items.fold(d) { acc, it -> logFood(acc, date, slot, it.foodId, it.servings * k, at, it.amount?.times(k), it.unit) }
    }

    /** "Same as yesterday": copy a slot from another day. */
    fun copySlot(d: DietData, from: LocalDate, to: LocalDate, slot: String, at: Long): DietData {
        val items = d.log.filter { it.date == from.toString() && it.slot == slot }
        return items.fold(d) { acc, e -> logFood(acc, to, slot, e.foodId, e.servings, at, e.amount, e.unit) }
    }

    /** Save what's logged in a slot as a reusable meal. */
    fun saveSlotAsMeal(d: DietData, date: LocalDate, slot: String, name: String): DietData {
        val items = d.log.filter { it.date == date.toString() && it.slot == slot }.map { MealItem(it.foodId, it.servings, it.amount, it.unit) }
        if (items.isEmpty()) return d
        return d.copy(meals = d.meals + SavedMeal(slug(name, d.meals.map { it.id }.toSet()), name.trim(), items))
    }

    fun saveMeal(d: DietData, m: SavedMeal) =
        if (d.meals.any { it.id == m.id }) d.copy(meals = d.meals.map { if (it.id == m.id) m else it }) else d.copy(meals = d.meals + m)

    fun deleteMeal(d: DietData, id: String) = d.copy(meals = d.meals.filter { it.id != id })

    fun saveFood(d: DietData, f0: Food): DietData {
        val f = Units.enrich(f0)
        return if (d.foods.any { it.id == f.id }) d.copy(foods = d.foods.map { if (it.id == f.id) f else it }) else d.copy(foods = d.foods + f)
    }

    fun newFoodId(d: DietData, name: String) = slug(name, d.foods.map { it.id }.toSet())

    fun addWater(d: DietData, date: LocalDate, oz: Double, at: Long) =
        if (oz == 0.0) d else d.copy(water = d.water + WaterEntry(date.toString(), at, oz))

    fun setWeight(d: DietData, date: LocalDate, lb: Double, at: Long): DietData {
        if (lb !in 60.0..700.0) return d
        return d.copy(weights = d.weights.filter { it.date != date.toString() } + WeightEntry(date.toString(), at, lb))
    }

    fun checkIn(d: DietData, date: LocalDate, clarity: Int, energy: Int, at: Long, note: String = "") = d.copy(
        checkins = d.checkins.filter { it.date != date.toString() } +
            CheckIn(date.toString(), at, clarity.coerceIn(1, 5), energy.coerceIn(1, 5), note),
    )

    fun setComplete(d: DietData, date: LocalDate, complete: Boolean) =
        d.copy(completeDays = if (complete) d.completeDays + date.toString() else d.completeDays - date.toString())

    fun setTraining(d: DietData, date: LocalDate, training: Boolean?) =
        d.copy(trainingOverride = if (training == null) d.trainingOverride - date.toString() else d.trainingOverride + (date.toString() to training))

    fun putActivity(d: DietData, a: DayActivity): DietData {
        val old = d.activity.firstOrNull { it.date == a.date }
        val merged = if (old == null) a else old.copy(
            activeKcal = a.activeKcal ?: old.activeKcal, steps = a.steps ?: old.steps,
            liftMin = a.liftMin ?: old.liftMin, liftStartAt = a.liftStartAt ?: old.liftStartAt, updatedAt = a.updatedAt,
            sleepH = a.sleepH ?: old.sleepH,
        )
        if (merged == old) return d
        return d.copy(activity = (d.activity.filter { it.date != a.date } + merged).sortedBy { it.date }.takeLast(400))
    }

    fun saveExperiment(d: DietData, e: Experiment) =
        d.copy(experiments = if (d.experiments.any { it.id == e.id }) d.experiments.map { if (it.id == e.id) e else it } else d.experiments + e)

    fun deleteExperiment(d: DietData, id: String) = d.copy(experiments = d.experiments.filter { it.id != id })

    fun markExperimentDay(d: DietData, id: String, date: LocalDate, followed: Boolean?, gym: Int? = null) = d.copy(experiments = d.experiments.map { e ->
        if (e.id != id) e else e.copy(
            followed = if (followed == null) e.followed - date.toString() else e.followed + (date.toString() to followed),
            gym = if (gym == null) e.gym else e.gym + (date.toString() to gym),
        )
    })

    /** Log a planned day (meal planner) into the diary, slot by slot. */
    fun logPlan(d: DietData, date: LocalDate, plan: MealPlanner.DayPlan, at: Long): DietData = plan.slots.fold(d) { acc, s ->
        s.items.fold(acc) { x, it ->
            when (it) {
                is MealPlanner.Pick.FoodPick -> logFood(x, date, s.slot, it.foodId, it.servings, at)
                is MealPlanner.Pick.MealPick -> logMeal(x, date, s.slot, it.mealId, it.portions, at)
            }
        }
    }

    fun profile(d: DietData, f: (Profile) -> Profile) = d.copy(profile = f(d.profile))
    fun settings(d: DietData, f: (Settings) -> Settings) = d.copy(settings = f(d.settings))
}
