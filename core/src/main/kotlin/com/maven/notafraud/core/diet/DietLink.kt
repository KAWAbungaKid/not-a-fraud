package com.maven.notafraud.core.diet

import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.RoutineCategory
import com.maven.notafraud.core.model.RoutineItem
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.store.Actions
import kotlinx.serialization.Serializable

/**
 * What Not a Twig tells Not a Fraud (content://com.maven.diet.sync/status, docs/diet/STATE.md § 4).
 * Field names are the contract; everything has a default so a newer Twig can add fields.
 */
@Serializable
data class DietStatus(
    val schema: Int = 1,
    val generatedAt: Long = 0,
    val today: String = "",
    val kcal: Int = 0, val kcalTarget: Int = 0,
    val protein: Int = 0, val proteinTarget: Int = 0,
    val carbs: Int = 0, val carbsTarget: Int = 0,
    val fat: Int = 0, val fatTarget: Int = 0,
    val waterOz: Int = 0, val waterTargetOz: Int = 0,
    val trainingDay: Boolean = false,
    val weighedToday: Boolean = false,
    val trendLb: Double? = null,
    val ratePerWeekLb: Double? = null,
    val fasting: Boolean = false,
    val inEatingWindow: Boolean = true,
    val hoursSinceFood: Double? = null,
    /** Meal slots with food logged today (Lunch, Pre-workout, Supper, Snacks…). */
    val slotsLogged: List<String> = emptyList(),
    /** Overdue things, loudest first. */
    val nags: List<SyncNag> = emptyList(),
    /** Today's 1–5 check-in, null until done. */
    val clarity: Int? = null,
    val energy: Int? = null,
)

/** kind MEAL / WATER / WEIGH_IN / CHECK_IN; key "meal:Lunch", "water", "weigh", "checkin"; level 1–3. */
@Serializable
data class SyncNag(val kind: String = "", val key: String = "", val title: String = "", val text: String = "", val level: Int = 1)

object DietLink {
    /** Routine ids for Twig reminders that have no routine item of their own (water, weigh-in, check-in…). */
    const val PREFIX = "twig:"

    /** Twig's slot names, for linking meal items by name. */
    val KNOWN_SLOTS = listOf("Breakfast", "Pre-workout", "Lunch", "Supper", "Dinner", "Post-workout", "Snacks")

    /** The last status, only if it was made today. */
    fun today(d: AppData, now: Long): DietStatus? {
        val s = d.diet ?: return null
        return s.takeIf { it.today == Time.dateOf(now, Time.zone(d.settings.zoneId)).toString() }
    }

    /** Twig owns this meal right now: connected, routing on, and the item is linked to a slot. */
    fun owns(d: AppData, item: RoutineItem): Boolean =
        item.category == RoutineCategory.MEAL && !item.dietSlot.isNullOrBlank() && d.diet != null && d.settings.dietNags

    fun slotLogged(s: DietStatus, slot: String) = s.slotsLogged.any { it.equals(slot, ignoreCase = true) }

    fun mealNag(s: DietStatus, slot: String): SyncNag? = s.nags.firstOrNull { it.kind == "MEAL" && it.key.equals("meal:$slot", ignoreCase = true) }

    /** Twig reminders that don't belong to a linked routine meal: water, weigh-in, check-in, unlinked slots. */
    fun extraNags(d: AppData, now: Long): List<SyncNag> {
        if (!d.settings.dietNags) return emptyList()
        val s = today(d, now) ?: return emptyList()
        val linked = d.routine.filter { it.enabled && it.category == RoutineCategory.MEAL }.mapNotNull { it.dietSlot?.lowercase() }.toSet()
        return s.nags.filter { n -> !(n.kind == "MEAL" && n.key.removePrefix("meal:").lowercase() in linked) }
    }

    /** "Lunch" for an item called "Lunch" or "Pre-workout meal". */
    fun guessSlot(title: String): String? =
        KNOWN_SLOTS.firstOrNull { title.trim().equals(it, ignoreCase = true) || title.trim().startsWith("$it ", ignoreCase = true) }

    /** One-line summary for "How's today?" and the Now page. */
    fun summary(s: DietStatus): String = listOfNotNull(
        s.clarity?.let { "clarity $it/5" },
        s.energy?.let { "energy $it/5" },
        "${s.kcal} of ${s.kcalTarget} kcal",
        "protein ${s.protein}/${s.proteinTarget} g",
        "water ${s.waterOz}/${s.waterTargetOz} oz",
        s.hoursSinceFood?.let { "%.1f h since food".format(it) },
    ).joinToString(" · ")

    /** Today's check-in says brain fog or low energy (1–2 of 5). */
    fun lowCheckIn(s: DietStatus?): Boolean = s != null && ((s.clarity ?: 5) <= 2 || (s.energy ?: 5) <= 2)

    /** New status in: store it, tick meal items whose Twig slot has food logged. */
    fun receive(d: AppData, status: DietStatus, now: Long): AppData {
        var x = d.copy(diet = status)
        val date = Time.dateOf(now, Time.zone(d.settings.zoneId))
        if (status.today != date.toString()) return x
        val done = x.routineDays.firstOrNull { it.date == date.toString() }?.done ?: emptyMap()
        for (item in x.routine) {
            val slot = item.dietSlot ?: continue
            if (!item.enabled || item.category != RoutineCategory.MEAL || item.id in done) continue
            if (slotLogged(status, slot)) x = Actions.checkRoutine(x, item.id, now, "Not a Twig")
        }
        return x
    }
}
