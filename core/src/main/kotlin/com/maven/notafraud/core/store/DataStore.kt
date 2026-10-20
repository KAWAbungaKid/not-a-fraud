package com.maven.notafraud.core.store

import com.maven.notafraud.core.model.AppData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The whole app state as one JSON file. It is small (well under 1 MB), so every change is
 * written immediately and atomically (write temp file, then rename). One daily backup is kept.
 * Phone and PC each have their own file; sync merges them later.
 */
class DataStore(private val file: File, seed: () -> AppData) {

    private val _state = MutableStateFlow(load() ?: seed().also { write(it) })
    val state: StateFlow<AppData> = _state.asStateFlow()
    val current: AppData get() = _state.value

    @Synchronized
    fun update(transform: (AppData) -> AppData): AppData {
        val before = _state.value
        val after = Prune.apply(transform(before), System.currentTimeMillis())
        if (after != before) {
            _state.value = after
            write(after)
        }
        return after
    }

    fun exportJson(): String = JSON.encodeToString(AppData.serializer(), current)

    /** Replaces everything. Caller confirms with the user first. */
    fun importJson(text: String): Result<AppData> = runCatching {
        val data = JSON.decodeFromString(AppData.serializer(), text)
        update { data }
    }

    private fun load(): AppData? {
        val candidates = listOf(file, File(file.path + ".bak"))
        for (f in candidates) {
            if (!f.exists()) continue
            val parsed = runCatching { JSON.decodeFromString(AppData.serializer(), f.readText()) }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun write(data: AppData) {
        file.parentFile?.mkdirs()
        val bak = File(file.path + ".bak")
        if (file.exists() && (!bak.exists() || System.currentTimeMillis() - bak.lastModified() > 86_400_000L)) {
            runCatching { file.copyTo(bak, overwrite = true) }
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(JSON.encodeToString(AppData.serializer(), data))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = false
            coerceInputValues = true
        }
    }
}

/** Keeps the file from growing without bound. */
object Prune {
    private const val DAY = 86_400_000L
    fun apply(d: AppData, now: Long): AppData = d.copy(
        events = if (d.events.size > 400) d.events.filter { it.end > now - 30 * DAY } else d.events,
        messages = d.messages.takeLast(200),
        usage = d.usage.sortedBy { it.date }.takeLast(120),
        days = d.days.sortedBy { it.date }.takeLast(400),
    )
}
