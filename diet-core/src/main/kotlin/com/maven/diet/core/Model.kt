package com.maven.diet.core

import kotlinx.serialization.Serializable

/**
 * Everything Not a Twig stores, as one JSON file (same pattern as Not a Fraud / Not a Troglodyte).
 * Dates are ISO strings ("2026-11-02"), timestamps epoch millis, weights in lb, water in fl oz.
 */
@Serializable
data class DietData(
    val version: Int = Migration.CURRENT,
    val profile: Profile = Profile(),
    val foods: List<Food> = emptyList(),
    val meals: List<SavedMeal> = emptyList(),
    val log: List<LogEntry> = emptyList(),
    val water: List<WaterEntry> = emptyList(),
    val weights: List<WeightEntry> = emptyList(),
    val checkins: List<CheckIn> = emptyList(),
    /** Activity per day from Health Connect (Samsung Health) and the workout app. */
    val activity: List<DayActivity> = emptyList(),
    /** Days the user marked "that's everything I ate" (or that were obviously complete). Used by the TDEE estimate. */
    val completeDays: Set<String> = emptySet(),
    /** Per-day override of training vs rest targets ("2026-11-02" → true = training). */
    val trainingOverride: Map<String, Boolean> = emptyMap(),
    val settings: Settings = Settings(),
) {
    fun food(id: String) = foods.firstOrNull { it.id == id }
    fun meal(id: String) = meals.firstOrNull { it.id == id }
}

@Serializable enum class Sex { MALE, FEMALE }

@Serializable
enum class Goal(val label: String, val lbPerWeek: Double) {
    CUT("Cut (−0.75 lb/wk)", -0.75),
    RECOMP("Recomp (hold weight)", 0.0),
    LEAN_BULK("Lean bulk (+0.25 lb/wk)", 0.25),
    BULK("Bulk (+0.5 lb/wk)", 0.5),
}

@Serializable
data class Profile(
    val heightIn: Double = 72.0,
    val age: Int = 21,
    val sex: Sex = Sex.MALE,
    /** Starting weight; the daily weigh-ins take over. */
    val weightLb: Double = 200.0,
    /** Rough body fat %, enables the lean-mass (Katch-McArdle) formula. Null = Mifflin-St Jeor. */
    val bodyFatPct: Double? = 13.5,
    val goal: Goal = Goal.RECOMP,
)

/** Nutrients beyond the big four, per serving. Keys are stable (sync / backups use them). */
@Serializable
enum class Nutrient(val label: String, val unit: String, val dailyTarget: Double?, val isLimit: Boolean = false) {
    FIBER("Fiber", "g", 38.0),
    SUGAR("Sugar", "g", null),
    SAT_FAT("Saturated fat", "g", 22.0, isLimit = true),
    SODIUM("Sodium", "mg", 2300.0, isLimit = true),
    POTASSIUM("Potassium", "mg", 3400.0),
    CALCIUM("Calcium", "mg", 1000.0),
    IRON("Iron", "mg", 8.0),
    VITAMIN_D("Vitamin D", "mcg", 15.0),
    MAGNESIUM("Magnesium", "mg", 400.0),
    CHOLESTEROL("Cholesterol", "mg", null),
}

@Serializable
data class Food(
    val id: String,
    val name: String,
    val brand: String = "",
    /** What one serving is, as printed: "1 cup (240 ml)", "4 oz (112 g)". */
    val serving: String = "1 serving",
    /** Grams in one serving if known (lets you log by weight). */
    val servingGrams: Double? = null,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val nutrients: Map<Nutrient, Double> = emptyMap(),
    /** "label" (typed by you), "builtin" (approximate, from USDA / typical labels). */
    val source: String = "label",
    val archived: Boolean = false,
)

@Serializable
data class LogEntry(
    val id: String,
    val date: String,
    /** Meal slot name (Settings.slots). */
    val slot: String,
    val foodId: String,
    val servings: Double,
    val at: Long,
)

@Serializable
data class MealItem(val foodId: String, val servings: Double)

/** A saved meal ("my usual lunch") or a recipe (items make [servings] portions; logging 1 = one portion). */
@Serializable
data class SavedMeal(
    val id: String,
    val name: String,
    val items: List<MealItem>,
    val servings: Double = 1.0,
    val instructions: String = "",
)

@Serializable data class WaterEntry(val date: String, val at: Long, val oz: Double)
@Serializable data class WeightEntry(val date: String, val at: Long, val lb: Double)

/** Daily 2-tap check-in: the two things Maven cares about. 1 = bad, 5 = great. */
@Serializable
data class CheckIn(
    val date: String,
    val at: Long,
    /** 1 = heavy brain fog, 5 = clear. */
    val clarity: Int,
    /** 1 = drained, 5 = lots (gym / cardio energy). */
    val energy: Int,
    val note: String = "",
)

@Serializable
data class DayActivity(
    val date: String,
    /** Active calories from Health Connect (Samsung Health), so far today / whole day for past days. */
    val activeKcal: Double? = null,
    val steps: Long? = null,
    /** Minutes lifted (workout app) and when the workout started. */
    val liftMin: Int? = null,
    val liftStartAt: Long? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class Slot(
    val name: String,
    /** "HH:mm" — nag if nothing logged in this slot by then. Null = no nag (snacks). */
    val by: String? = null,
)

@Serializable
data class Settings(
    val slots: List<Slot> = listOf(Slot("Lunch", "13:00"), Slot("Pre-workout", "16:30"), Slot("Supper", "19:30"), Slot("Snacks")),
    /** Intermittent fasting: eat between these ("HH:mm"). Off = no window. */
    val fasting: Boolean = true,
    val windowStart: String = "12:00",
    val windowEnd: String = "20:00",
    val proteinPerLb: Double = 0.95,
    val fatPerLb: Double = 0.35,
    /** BMR multiplier for a normal week (campus walking + daily cardio + lifting). Only used until your own estimate exists. */
    val activityFactor: Double = 1.45,
    /** Training days get this many more calories (as carbs), rest days this many fewer. Recomp default. */
    val cycleKcal: Int = 150,
    /** Extra food when you've moved more than a normal day: share of the extra active calories eaten back. */
    val eatBackShare: Double = 0.7,
    val waterOz: Double = 100.0,
    val waterTrainingExtraOz: Double = 20.0,
    val wake: String = "06:00",
    val bed: String = "22:00",
    val weighInBy: String = "09:00",
    val checkInAfter: String = "14:00",
    val nags: Boolean = true,
    val nagEveryMin: Int = 45,
)
