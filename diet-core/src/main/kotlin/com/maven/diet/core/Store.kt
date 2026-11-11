package com.maven.diet.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** One JSON file, atomic writes, one daily .bak. */
class DietStore(private val file: File, seed: () -> DietData) {
    private val _state = MutableStateFlow(
        load()?.let { old -> Migration.apply(old).also { if (it != old) write(it) } } ?: seed().also { write(it) }
    )
    val state: StateFlow<DietData> = _state.asStateFlow()
    val current: DietData get() = _state.value
    var onChange: ((DietData) -> Unit)? = null

    @Synchronized
    fun update(transform: (DietData) -> DietData): DietData {
        val before = _state.value
        val after = transform(before)
        if (after != before) {
            _state.value = after
            write(after)
            onChange?.invoke(after)
        }
        return after
    }

    fun exportJson(): String = JSON.encodeToString(DietData.serializer(), current)

    fun importJson(text: String): Result<DietData> = runCatching {
        val data = JSON.decodeFromString(DietData.serializer(), text)
        update { Migration.apply(data) }
    }

    private fun load(): DietData? {
        for (f in listOf(file, File(file.path + ".bak"))) {
            if (!f.exists()) continue
            runCatching { JSON.decodeFromString(DietData.serializer(), f.readText()) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun write(data: DietData) {
        file.parentFile?.mkdirs()
        val bak = File(file.path + ".bak")
        if (file.exists() && (!bak.exists() || System.currentTimeMillis() - bak.lastModified() > 86_400_000L)) {
            runCatching { file.copyTo(bak, overwrite = true) }
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSON.encodeToString(DietData.serializer(), data))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    }
}

/**
 * What Not a Twig tells Not a Fraud. Served as JSON by content://com.maven.diet.sync/status.
 * Field names are a contract — add, don't rename.
 */
@Serializable
data class DietStatus(
    val schema: Int = 1,
    val generatedAt: Long,
    val today: String,
    val kcal: Int, val kcalTarget: Int,
    val protein: Int, val proteinTarget: Int,
    val carbs: Int, val carbsTarget: Int,
    val fat: Int, val fatTarget: Int,
    val waterOz: Int, val waterTargetOz: Int,
    val trainingDay: Boolean,
    val weighedToday: Boolean,
    val trendLb: Double?,
    val ratePerWeekLb: Double?,
    val fasting: Boolean,
    val inEatingWindow: Boolean,
    val hoursSinceFood: Double?,
    val slotsLogged: List<String>,
    /** Overdue things, loudest first: kind (MEAL/WATER/WEIGH_IN/CHECK_IN), key, title, text, level 1–3. */
    val nags: List<SyncNag>,
    val clarity: Int?,
    val energy: Int?,
    /** The meal schedule Not a Fraud mirrors: every slot with its "by" time (null = no time, like Snacks). */
    val slots: List<Slot> = emptyList(),
    val windowStart: String? = null,
    val windowEnd: String? = null,
    /** Slot → what you usually eat there, for one-tap logging from Not a Fraud's check-in. */
    val usual: Map<String, String> = emptyMap(),
    /** This Twig takes weigh-ins and quick meals from Not a Fraud (/weigh, /quickmeal). */
    val acceptsQuick: Boolean = true,
)

/**
 * Meal-slot edits coming from Not a Fraud (it mirrors the schedule; either app can change it).
 * [old] = the slot being changed (null = new), [name] = its name after, [by] = "HH:mm" or null, [remove] deletes [old].
 */
object MealSync {
    fun apply(s: Settings, old: String?, name: String?, by: String?, remove: Boolean): Settings {
        fun same(a: String, b: String?) = b != null && a.equals(b.trim(), ignoreCase = true)
        if (remove) return if (old == null || s.slots.size <= 1) s else s.copy(slots = s.slots.filterNot { same(it.name, old) })
        val target = name?.trim()?.takeIf { it.isNotEmpty() } ?: old?.trim() ?: return s
        val time = by?.takeIf { Regex("\\d{1,2}:\\d{2}").matches(it) }?.let { val (h, m) = it.split(":"); "%02d:%02d".format(h.toInt(), m.toInt()) }
        return when {
            // Already have a slot by that name: just move its time.
            s.slots.any { same(it.name, target) } -> s.copy(slots = s.slots.map { if (same(it.name, target)) it.copy(by = time) else it })
            // Renaming an existing slot.
            old != null && s.slots.any { same(it.name, old) } -> s.copy(slots = s.slots.map { if (same(it.name, old)) Slot(target, time) else it })
            else -> s.copy(slots = s.slots + Slot(target, time))
        }
    }
}

@Serializable
data class SyncNag(val kind: String, val key: String, val title: String, val text: String, val level: Int)

object Sync {
    fun status(d: DietData, now: Long, zone: ZoneId, workoutSaysLikely: Boolean?): DietStatus {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val t = Targets.forDay(d, today, workoutSaysLikely)
        val eaten = Diary.totals(d, today)
        val fast = Fasting.status(d, now, zone)
        val c = d.checkins.filter { it.date == today.toString() }.maxByOrNull { it.at }
        return DietStatus(
            generatedAt = now, today = today.toString(),
            kcal = eaten.kcal.toInt(), kcalTarget = t.macros.kcal.toInt(),
            protein = eaten.protein.toInt(), proteinTarget = t.macros.protein.toInt(),
            carbs = eaten.carbs.toInt(), carbsTarget = t.macros.carbs.toInt(),
            fat = eaten.fat.toInt(), fatTarget = t.macros.fat.toInt(),
            waterOz = Diary.waterOz(d, today).toInt(), waterTargetOz = t.waterOz.toInt(),
            trainingDay = t.training,
            weighedToday = d.weights.any { it.date == today.toString() },
            trendLb = Trend.latest(d, today),
            ratePerWeekLb = Trend.ratePerWeek(d, today),
            fasting = fast.enabled, inEatingWindow = fast.inWindow, hoursSinceFood = fast.hoursSinceFood,
            slotsLogged = Diary.slotsEaten(d, today).toList(),
            nags = Nags.due(d, now, zone, t.training).sortedByDescending { it.level }
                .map { SyncNag(it.kind.name, it.key, it.title, it.text, it.level) },
            clarity = c?.clarity, energy = c?.energy,
            slots = d.settings.slots,
            windowStart = d.settings.windowStart.takeIf { d.settings.fasting },
            windowEnd = d.settings.windowEnd.takeIf { d.settings.fasting },
            usual = d.settings.slots.mapNotNull { sl -> Quick.usual(d, sl.name, today)?.let { sl.name to it.label } }.toMap(),
        )
    }
}
