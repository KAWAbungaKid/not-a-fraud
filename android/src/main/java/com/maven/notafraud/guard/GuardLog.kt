package com.maven.notafraud.guard

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Why wasn't I blocked?" — the last 200 blocking decisions, kept in a small text file
 * (not the main data file, so app switches don't rewrite everything).
 */
object GuardLog {
    data class Entry(val at: Long, val subject: String, val decision: String)

    private const val MAX = 200
    private val entries = ArrayDeque<Entry>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("EEE h:mm:ss a", Locale.US)

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "guardlog.txt")
        runCatching {
            file!!.readLines().takeLast(MAX).forEach { line ->
                val p = line.split('\t')
                if (p.size == 3) entries.addLast(Entry(p[0].toLong(), p[1], p[2]))
            }
        }
    }

    @Synchronized
    fun add(subject: String, decision: String) {
        val last = entries.lastOrNull()
        if (last != null && last.subject == subject && last.decision == decision && System.currentTimeMillis() - last.at < 5_000) return
        entries.addLast(Entry(System.currentTimeMillis(), subject, decision))
        while (entries.size > MAX) entries.removeFirst()
        runCatching { file?.writeText(entries.joinToString("\n") { "${it.at}\t${it.subject}\t${it.decision.replace('\t', ' ')}" }) }
    }

    @Synchronized
    fun recent(): List<Entry> = entries.toList().asReversed()

    fun time(at: Long): String = fmt.format(Date(at))
}
