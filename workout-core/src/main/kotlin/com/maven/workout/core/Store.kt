package com.maven.workout.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/** One JSON file, atomic writes, one daily .bak — same as Not a Fraud's DataStore. */
class WorkoutStore(private val file: File, seed: () -> WorkoutData) {
    private val _state = MutableStateFlow(
        load()?.let { old -> Migration.apply(old).also { if (it != old) write(it) } } ?: seed().also { write(it) }
    )
    val state: StateFlow<WorkoutData> = _state.asStateFlow()
    val current: WorkoutData get() = _state.value

    /** Called after every successful change (the app uses it to tell Not a Fraud). */
    var onChange: ((WorkoutData) -> Unit)? = null

    @Synchronized
    fun update(transform: (WorkoutData) -> WorkoutData): WorkoutData {
        val before = _state.value
        val after = transform(before)
        if (after != before) {
            _state.value = after
            write(after)
            onChange?.invoke(after)
        }
        return after
    }

    fun exportJson(): String = JSON.encodeToString(WorkoutData.serializer(), current)

    fun importJson(text: String): Result<WorkoutData> = runCatching {
        val data = JSON.decodeFromString(WorkoutData.serializer(), text)
        update { Migration.apply(data) }
    }

    private fun load(): WorkoutData? {
        for (f in listOf(file, File(file.path + ".bak"))) {
            if (!f.exists()) continue
            runCatching { JSON.decodeFromString(WorkoutData.serializer(), f.readText()) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun write(data: WorkoutData) {
        file.parentFile?.mkdirs()
        val bak = File(file.path + ".bak")
        if (file.exists() && (!bak.exists() || System.currentTimeMillis() - bak.lastModified() > 86_400_000L)) {
            runCatching { file.copyTo(bak, overwrite = true) }
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSON.encodeToString(WorkoutData.serializer(), data))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
    }
}
