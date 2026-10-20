package com.maven.notafraud.desktop

import com.maven.notafraud.core.guard.DesktopPolicy
import com.maven.notafraud.core.guard.NagPolicy
import com.maven.notafraud.core.model.UsageDay
import com.maven.notafraud.core.plan.Planning
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.store.Actions
import com.maven.notafraud.core.store.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Samples the foreground window every 15 s (cheap: two Win32 calls). Idle time over 5 min
 * isn't counted. Minutes per process roll into today's usage row, saved once a minute.
 * Distractions (YouTube in a browser, blocked processes) trigger the same nag ladder as the
 * phone, and can optionally be minimized.
 */
class Tracker(private val store: DataStore, private val notify: (String, String) -> Unit) {

    data class Live(val process: String = "", val title: String = "", val idle: Boolean = false, val distraction: Boolean = false)

    private val _live = MutableStateFlow(Live())
    val live = _live.asStateFlow()

    @Volatile var minimizeDistractions = false

    private val pendingSeconds = HashMap<String, Long>()
    private var distractionSince = 0L
    private var nagLevel = 0
    private var lastFlush = System.currentTimeMillis()

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            runCatching { tick() }
            delay(SAMPLE_MS)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val fg = Win32.foreground()
        val idle = Win32.idleSeconds() > IDLE_LIMIT_S
        if (fg == null) { _live.value = Live(idle = idle); return }

        val d = store.current
        val distraction = !idle && DesktopPolicy.isDistraction(fg.process, fg.title, d.rules)
        _live.value = Live(fg.process, fg.title, idle, distraction)

        if (!idle) pendingSeconds[fg.process] = (pendingSeconds[fg.process] ?: 0L) + SAMPLE_MS / 1000

        if (distraction) {
            if (distractionSince == 0L) { distractionSince = now; nagLevel = 0 }
            val minutesIn = ((now - distractionSince) / Time.MIN).toInt()
            val plan = Planning.ensureToday(d, now).todayPlan!!
            val offsets = NagPolicy.offsetsMin(plan.roughDay)
            if (d.settings.nagsEnabled && nagLevel < offsets.size && minutesIn >= offsets[nagLevel]) {
                val state = Planning.nowState(d, now)
                if (!state.protected) {
                    val nag = NagPolicy.nag(nagLevel, DesktopPolicy.label(fg.process), minutesIn, state.suggested?.title, d.settings.creed, plan.roughDay)
                    notify(nag.title, nag.text)
                    if (minimizeDistractions && nagLevel >= 1) Win32.minimize(fg.hwnd)
                }
                nagLevel++
            }
        } else if (!idle) {
            distractionSince = 0L
            nagLevel = 0
        }

        if (now - lastFlush >= FLUSH_MS) flush(now)
    }

    fun flush(now: Long = System.currentTimeMillis()) {
        lastFlush = now
        if (pendingSeconds.isEmpty()) return
        val adds = HashMap(pendingSeconds)
        pendingSeconds.clear()
        store.update { d ->
            val zone = Time.zone(d.settings.zoneId)
            val date = Time.dateOf(now, zone).toString()
            val existing = d.usage.firstOrNull { it.date == date && it.device == DEVICE }
            // Stored in minutes; carry leftover seconds so 15 s samples don't round away.
            val secs = (existing?.minutesByApp?.mapValues { it.value * 60L } ?: emptyMap()).toMutableMap()
            val carry = carrySeconds
            adds.forEach { (p, s) -> carry[p] = (carry[p] ?: 0L) + s }
            val minutes = (secs.keys + carry.keys).associateWith { p ->
                val extra = (carry[p] ?: 0L) / 60
                carry[p] = (carry[p] ?: 0L) % 60
                ((secs[p] ?: 0L) / 60 + extra).toInt()
            }.filterValues { it > 0 }
            Actions.recordUsage(
                d,
                UsageDay(date = date, device = DEVICE, minutesByApp = minutes, labels = minutes.keys.associateWith { DesktopPolicy.label(it) }),
            )
        }
    }

    private val carrySeconds = HashMap<String, Long>()

    companion object {
        const val DEVICE = "pc"
        const val SAMPLE_MS = 15_000L
        const val FLUSH_MS = 60_000L
        const val IDLE_LIMIT_S = 300L
    }
}
