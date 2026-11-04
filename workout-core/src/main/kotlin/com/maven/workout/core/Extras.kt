package com.maven.workout.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Plate math for the bar, and warm-up ramps. */
object Plates {
    /** Plates per side for [total] on a [bar], greedy from the biggest you own. Leftover = what couldn't be made. */
    data class Load(val perSide: List<Double>, val leftover: Double)

    fun perSide(total: Double, bar: Double, plates: List<Double>): Load {
        var side = ((total - bar) / 2).coerceAtLeast(0.0)
        val out = ArrayList<Double>()
        for (p in plates.sortedDescending()) while (side >= p - 1e-6) { out += p; side -= p }
        return Load(out, (side * 2 * 100).roundToInt() / 100.0)
    }

    fun text(l: Load): String = if (l.perSide.isEmpty()) "just the bar" else
        l.perSide.groupBy { it }.entries.joinToString(" + ") { (p, xs) -> (if (xs.size > 1) "${xs.size}×" else "") + Rpe.fmt(p) } + " per side" +
            if (l.leftover > 0) " (${Rpe.fmt(l.leftover)} short)" else ""

    /** Bar × 10, then ~40 / 60 / 80 % with falling reps, rounded; ramps that collapse onto the bar are dropped. */
    fun warmups(top: Double, bar: Double, roundTo: Double): List<Pair<Double, Int>> {
        if (top <= bar * 1.3) return listOf(bar to 10)
        val steps = listOf(0.4 to 5, 0.6 to 3, 0.8 to 1).map { (f, r) -> Rpe.round(top * f, roundTo) to r }.filter { it.first > bar }
        return (listOf(bar to 10) + steps).distinctBy { it.first }
    }
}

/** Hard sets per muscle per week (done, non-warm-up sets), for spotting what gets neglected. */
object Volume {
    fun week(d: WorkoutData, start: LocalDate): Map<Muscle, Int> {
        val end = start.plusDays(7)
        val out = HashMap<Muscle, Int>()
        for (s in d.finished()) {
            val dt = LocalDate.parse(s.date)
            if (dt.isBefore(start) || !dt.isBefore(end)) continue
            for (e in s.entries) {
                if (e.skipped) continue
                val sets = e.sets.count { it.done && it.kind != SetKind.WARMUP }
                if (sets == 0) continue
                for (m in d.exercise(e.exerciseId)?.muscles.orEmpty()) out[m] = (out[m] ?: 0) + sets
            }
        }
        return out
    }
}

/**
 * Hevy history (Profile → Settings → Export data → CSV). Columns used: title, start_time, end_time, exercise_title,
 * set_type, weight_lbs or weight_kg, reps, duration_seconds, rpe, superset_id. Exercises match yours by name
 * (case and punctuation ignored); unknown ones are created so nothing is lost.
 */
object HevyImport {
    data class Result(val data: WorkoutData, val sessions: Int, val sets: Int, val newExercises: List<String>, val skipped: Int)

    private val DATE_FORMATS = listOf("d MMM yyyy, HH:mm", "dd MMM yyyy, HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss", "MMM d, yyyy, h:mm a")
        .map { DateTimeFormatter.ofPattern(it, Locale.US) }

    fun parseTime(s: String, zone: ZoneId): Long? {
        val t = s.trim().trim('"')
        for (f in DATE_FORMATS) runCatching { return LocalDateTime.parse(t, f).atZone(zone).toInstant().toEpochMilli() }
        return null
    }

    /** CSV with quoted fields (commas and quotes inside quotes). */
    fun rows(csv: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < csv.length) {
            val c = csv[i]
            when {
                quoted && c == '"' && i + 1 < csv.length && csv[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < csv.length && csv[i + 1] == '\n') i++
                    row += cell.toString(); cell.clear()
                    if (row.any { it.isNotBlank() }) out += row
                    row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); if (row.any { it.isNotBlank() }) out += row }
        return out
    }

    private fun key(name: String) = name.lowercase().replace(Regex("[^a-z0-9]"), "")

    fun import(d: WorkoutData, csv: String, zone: ZoneId): Result {
        val all = rows(csv)
        if (all.size < 2) return Result(d, 0, 0, emptyList(), 0)
        val h = all.first().map { it.trim().lowercase() }
        fun col(vararg names: String) = names.firstNotNullOfOrNull { n -> h.indexOf(n).takeIf { it >= 0 } }
        val cTitle = col("title"); val cStart = col("start_time"); val cEnd = col("end_time"); val cEx = col("exercise_title")
        val cType = col("set_type"); val cLb = col("weight_lbs"); val cKg = col("weight_kg"); val cReps = col("reps")
        val cDur = col("duration_seconds"); val cRpe = col("rpe"); val cSuper = col("superset_id")
        if (cStart == null || cEx == null) return Result(d, 0, 0, emptyList(), all.size - 1)
        var x = d
        val byKey = HashMap<String, String>()
        for (e in x.exercises) byKey[key(e.name)] = e.id
        val created = ArrayList<String>()
        var skipped = 0
        var sets = 0
        val existingStarts = x.sessions.map { it.startedAt }.toSet()
        val groups = all.drop(1).groupBy { (cTitle?.let { i -> it.getOrNull(i) } ?: "") + "|" + it.getOrNull(cStart) }
        val sessions = ArrayList<Session>()
        for ((_, rs) in groups) {
            val first = rs.first()
            val start = first.getOrNull(cStart)?.let { parseTime(it, zone) } ?: run { skipped += rs.size; continue }
            if (start in existingStarts) { skipped += rs.size; continue }
            val end = cEnd?.let { first.getOrNull(it) }?.let { parseTime(it, zone) } ?: (start + 60 * 60_000)
            val entries = LinkedHashMap<String, MutableList<SetLog>>()
            val supers = HashMap<String, Int>()
            for (r in rs) {
                val name = r.getOrNull(cEx)?.trim().orEmpty()
                if (name.isEmpty()) { skipped++; continue }
                val exId = byKey[key(name)] ?: run {
                    val dur = cDur?.let { r.getOrNull(it)?.toDoubleOrNull() }
                    val hasWeight = (cLb?.let { r.getOrNull(it)?.toDoubleOrNull() } ?: cKg?.let { r.getOrNull(it)?.toDoubleOrNull() }) != null
                    val ex = Actions.newExercise(x, name).copy(measure = if (dur != null && dur > 0 && !hasWeight) Measure.TIME else if (hasWeight) Measure.WEIGHT_REPS else Measure.REPS)
                    x = Actions.saveExercise(x, ex)
                    byKey[key(name)] = ex.id
                    created += name
                    ex.id
                }
                val lb = cLb?.let { r.getOrNull(it)?.toDoubleOrNull() } ?: cKg?.let { r.getOrNull(it)?.toDoubleOrNull()?.times(2.20462) }
                val type = cType?.let { r.getOrNull(it)?.lowercase() }.orEmpty()
                entries.getOrPut(exId) { ArrayList() } += SetLog(
                    kind = if (type.contains("warm")) SetKind.WARMUP else SetKind.WORK,
                    weight = lb?.let { (it * 10).roundToInt() / 10.0 },
                    reps = cReps?.let { r.getOrNull(it)?.toDoubleOrNull()?.toInt() },
                    seconds = cDur?.let { r.getOrNull(it)?.toDoubleOrNull()?.toInt() }?.takeIf { it > 0 },
                    rpe = cRpe?.let { r.getOrNull(it)?.toDoubleOrNull() },
                    done = true, doneAt = start,
                )
                cSuper?.let { r.getOrNull(it) }?.takeIf { it.isNotBlank() }?.let { sid -> supers[exId] = sid.hashCode() and 0x7fff }
                sets++
            }
            if (entries.isEmpty()) continue
            val date = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate().toString()
            sessions += Session(
                id = Actions.newId(), name = cTitle?.let { first.getOrNull(it) }?.ifBlank { null } ?: "Hevy workout",
                date = date, startedAt = start, endedAt = end,
                entries = entries.map { (id, ss) -> Entry(id, ss, superset = supers[id]) }, source = "hevy",
            )
        }
        x = x.copy(sessions = (x.sessions + sessions).sortedBy { it.startedAt })
        return Result(x, sessions.size, sets, created, skipped)
    }
}
