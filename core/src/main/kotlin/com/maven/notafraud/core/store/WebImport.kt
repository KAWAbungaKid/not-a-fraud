package com.maven.notafraud.core.store

import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.CalEvent
import com.maven.notafraud.core.model.Energy
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.Project
import com.maven.notafraud.core.model.ProjectKind
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskStatus
import com.maven.notafraud.core.model.WorkSession
import com.maven.notafraud.core.model.newId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Imports data from the Project Max web app (STATE.md section 2 data model):
 *   projects {id,name,kind} · tasks {title,projectId,estMin,dueISO,priority,energy,minBlockMin,status,loggedMin,...}
 *   events {uid,title,startISO,endISO} · sessions {taskId,projectId,title,startISO,endISO,minutes}
 * Each collection may be an array or an id->object map. Merges into existing data:
 * projects match by name, tasks by title + project, events by uid.
 */
object WebImport {
    data class Result(val data: AppData, val projects: Int, val tasks: Int, val events: Int, val sessions: Int)

    fun merge(existing: AppData, text: String): Result {
        val root = Json.parseToJsonElement(text).jsonObject
        val src = (root["collections"] as? JsonObject) ?: (root["data"] as? JsonObject) ?: root

        val projectIdMap = HashMap<String, String>()
        val projects = existing.projects.toMutableList()
        var addedProjects = 0
        for ((key, o) in entries(src["projects"])) {
            val name = o.str("name") ?: continue
            val match = projects.firstOrNull { it.name.equals(name, ignoreCase = true) }
            val id = match?.id ?: newId().also {
                projects += Project(id = it, name = name, kind = kind(o.str("kind")))
                addedProjects++
            }
            projectIdMap[o.str("id") ?: key] = id
        }

        val taskIdMap = HashMap<String, String>()
        val tasks = existing.tasks.toMutableList()
        var addedTasks = 0
        for ((key, o) in entries(src["tasks"])) {
            val title = o.str("title") ?: continue
            val pid = o.str("projectId")?.let { projectIdMap[it] }
            val dup = tasks.firstOrNull { it.title.equals(title, ignoreCase = true) && it.projectId == pid }
            if (dup != null) { taskIdMap[o.str("id") ?: key] = dup.id; continue }
            val t = Task(
                title = title,
                projectId = pid,
                estMin = o.int("estMin") ?: 30,
                dueAt = time(o.str("dueISO") ?: o.str("due")),
                priority = when (o.str("priority")?.lowercase()) { "high" -> Priority.HIGH; "low" -> Priority.LOW; else -> Priority.NORMAL },
                energy = when (o.str("energy")?.lowercase()) { "low" -> Energy.LOW; "high" -> Energy.HIGH; else -> Energy.MED },
                minBlockMin = o.int("minBlockMin") ?: 15,
                status = if (o.str("status")?.lowercase() == "done") TaskStatus.DONE else TaskStatus.TODO,
                loggedMin = o.int("loggedMin") ?: 0,
                createdAt = time(o.str("createdISO")) ?: System.currentTimeMillis(),
                lastWorkedAt = time(o.str("lastWorkedISO")),
                doneAt = time(o.str("doneISO")),
                icsUid = o.str("icsUid"),
            )
            tasks += t
            taskIdMap[o.str("id") ?: key] = t.id
            addedTasks++
        }

        val events = existing.events.associateBy { it.uid }.toMutableMap()
        var addedEvents = 0
        for ((key, o) in entries(src["events"])) {
            val start = time(o.str("startISO")) ?: continue
            val end = time(o.str("endISO")) ?: (start + 3_600_000L)
            val uid = o.str("uid") ?: key
            if (uid !in events) addedEvents++
            events[uid] = CalEvent(uid = uid, title = o.str("title") ?: "(event)", start = start, end = end, source = "ics")
        }

        val sessions = existing.sessions.toMutableList()
        var addedSessions = 0
        for ((_, o) in entries(src["sessions"])) {
            val start = time(o.str("startISO")) ?: continue
            val end = time(o.str("endISO")) ?: continue
            if (sessions.any { it.start == start }) continue
            sessions += WorkSession(
                taskId = o.str("taskId")?.let { taskIdMap[it] },
                projectId = o.str("projectId")?.let { projectIdMap[it] },
                title = o.str("title") ?: "Session",
                start = start, end = end,
                minutes = o.int("minutes") ?: ((end - start) / 60_000).toInt(),
            )
            addedSessions++
        }

        return Result(
            existing.copy(projects = projects, tasks = tasks, events = events.values.sortedBy { it.start }, sessions = sessions),
            addedProjects, addedTasks, addedEvents, addedSessions,
        )
    }

    private fun entries(e: JsonElement?): List<Pair<String, JsonObject>> = when (e) {
        is JsonArray -> e.mapIndexedNotNull { i, x -> (x as? JsonObject)?.let { "$i" to unwrap(it) } }
        is JsonObject -> e.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to unwrap(it) } }
        else -> emptyList()
    }

    /** Some exports wrap a document as {id, data:{...}}. */
    private fun unwrap(o: JsonObject): JsonObject {
        val inner = o["data"] as? JsonObject ?: return o
        val id = o["id"] ?: o["doc_id"]
        return if (id != null && "id" !in inner) JsonObject(inner + ("id" to id)) else inner
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()

    private fun kind(s: String?) = when (s?.lowercase()) {
        "school" -> ProjectKind.SCHOOL
        "research" -> ProjectKind.RESEARCH
        "admin" -> ProjectKind.ADMIN
        "life" -> ProjectKind.LIFE
        else -> ProjectKind.PROJECT
    }

    private fun time(s: String?): Long? {
        s ?: return null
        return runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.of("America/Chicago")).toInstant().toEpochMilli() }.getOrNull()
            ?: s.toLongOrNull()
    }
}
