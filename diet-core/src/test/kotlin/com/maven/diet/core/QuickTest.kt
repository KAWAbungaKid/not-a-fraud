package com.maven.diet.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 0.4: quick meals from Not a Fraud, the usual, macro breakdown, fill-the-gap suggestions. */
class QuickTest {
    private val z = ZoneId.of("America/Chicago")
    private val mon = LocalDate.parse("2026-11-02")
    private val tue = mon.plusDays(1)
    private val seed = Seed.data(mon.toString())
    private fun at(s: String) = LocalDateTime.parse(s).atZone(z).toInstant().toEpochMilli()
    private val lunch get() = seed.settings.slots.first().name

    @Test
    fun `the usual copies the last day's slot at the time you ate`() {
        var d = Actions.logFood(seed, mon, lunch, "chicken-breast-raw", 2.0, at("2026-11-02T12:10"))
        d = Actions.logFood(d, mon, lunch, "rice-white-dry", 2.0, at("2026-11-02T12:10"))
        val u = assertNotNull(Quick.usual(d, lunch, tue))
        assertTrue(u.label.contains("kcal"), u.label)
        d = Quick.logUsual(d, lunch.lowercase(), tue, at("2026-11-03T12:30"))
        val today = Diary.entries(d, tue)
        assertEquals(2, today.size)
        assertTrue(today.all { it.at == at("2026-11-03T12:30") && it.slot == lunch })
    }

    @Test
    fun `a quick meal counts for fasting and the slot until foods are added`() {
        var d = Quick.add(seed, lunch, "chicken and rice", tue, at("2026-11-03T12:30"))
        assertTrue(lunch in Diary.slotsEaten(d, tue))
        val f = Fasting.status(d, at("2026-11-03T14:30"), z)
        assertEquals(2.0, f.hoursSinceFood!!, 0.01)
        assertTrue(Nags.due(d, at("2026-11-03T15:00"), z, true).none { it.key == "meal:$lunch" })
        val q = Quick.pendingFor(d, tue, lunch)!!
        d = Quick.resolve(d, q.id)
        assertTrue(Diary.pendingQuick(d, tue).isEmpty())
    }

    @Test
    fun `macro breakdown and fill-the-gap`() {
        var d = Actions.logFood(seed, mon, lunch, "chicken-breast-raw", 3.0, at("2026-11-02T12:10"))
        d = Actions.logFood(d, mon, lunch, "choc-milk", 1.0, at("2026-11-02T12:10"))
        val p = Breakdown.contributors(d, mon, Breakdown.Macro.PROTEIN)
        assertTrue(p.sumOf { it.pct } in 98..102)
        assertTrue(p.first().name.contains("hicken"), p.toString())
        val target = Targets.forDay(d, mon, true).macros
        val picks = FillGap.suggest(d, mon, target, Breakdown.Macro.CARBS)
        assertTrue(picks.isNotEmpty())
        for (k in picks) assertTrue(k.food.carbs * 4 / k.food.kcal >= 0.55, k.food.name)
    }
}
