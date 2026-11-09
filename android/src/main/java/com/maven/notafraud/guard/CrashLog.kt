package com.maven.notafraud.guard

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Crashes and caught guard errors, kept so "Why wasn't I blocked?" can show them (and Maven can paste them to fix
 * the bug). Last 8, newest first, in filesDir/crashes.txt. The default handler still runs after recording.
 */
object CrashLog {
    private const val MAX = 8
    private const val SEP = "\n=====\n"
    private var file: File? = null
    private val lastAt = HashMap<String, Long>()

    fun install(ctx: Context) {
        file = File(ctx.filesDir, "crashes.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write("CRASH on ${t.name}", e) }
            previous?.uncaughtException(t, e)
        }
    }

    /** A caught error inside the guard: recorded at most once a minute per place, so a bad loop can't flood it. */
    fun record(where: String, e: Throwable) {
        val now = System.currentTimeMillis()
        synchronized(lastAt) {
            if (now - (lastAt[where] ?: 0) < 60_000) return
            lastAt[where] = now
        }
        runCatching { write("caught in $where", e) }
    }

    @Synchronized
    private fun write(what: String, e: Throwable) {
        val f = file ?: return
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        val entry = "${GuardLog.time(System.currentTimeMillis())} · $what\n" + sw.toString().lines().take(30).joinToString("\n")
        val old = runCatching { f.readText().split(SEP).filter { it.isNotBlank() } }.getOrDefault(emptyList())
        f.writeText((listOf(entry) + old).take(MAX).joinToString(SEP))
    }

    fun entries(ctx: Context): List<String> =
        runCatching { File(ctx.filesDir, "crashes.txt").readText().split(SEP).filter { it.isNotBlank() } }.getOrDefault(emptyList())

    fun clear(ctx: Context) { runCatching { File(ctx.filesDir, "crashes.txt").delete() } }
}
