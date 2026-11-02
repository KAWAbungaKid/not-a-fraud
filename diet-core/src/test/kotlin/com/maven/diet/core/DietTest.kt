package com.maven.diet.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DietTest {
    private val z = ZoneId.of("America/Chicago")
    private val mon = LocalDate.parse("2026-11-02")
    private val seed = Seed.data(mon.toString())
    private fun at(s: String) = LocalDateTime.parse(s).atZone(z).toInstant().toEpochMilli()

    @Test
    fun `formula targets for Maven`() {
        // Katch-McArdle: 200 lb, 13.5% → LBM 78.47 kg → 370 + 21.6 × 78.47 = 2065
        assertEquals(2065.0, Targets.bmr(seed.profile, 200.0), 1.0)
        val noBf = seed.profile.copy(bodyFatPct = null)
        // Mifflin: 10 × 90.72 + 6.25 × 182.88 − 5 × 21 + 5 = 1950
        assertEquals(1950.3, Targets.bmr(noBf, 200.0), 1.0)
        val t = Targets.forDay(seed, mon, workoutSaysLikely = true)
        assertTrue(t.training)
        assertEquals("formula", t.maintenanceSource)
        assertEquals(2065.0 * 1.45 + 150, t.macros.kcal, 2.0)
        assertEquals(190.0, t.macros.protein, 0.01)
        assertEquals(70.0, t.macros.fat, 0.01)
        assertEquals((t.macros.kcal - 190 * 4 - 70 * 9) / 4, t.macros.carbs, 0.01)
        val rest = Targets.forDay(seed, mon, workoutSaysLikely = false)
        assertEquals(300.0, t.macros.kcal - rest.macros.kcal, 0.01)
        assertEquals(120.0, t.waterOz)
    }

    @Test
    fun `logging totals and meals`() {
        var d = Actions.logFood(seed, mon, "Lunch", "chicken-breast-raw", 2.0, at("2026-11-02T12:10"))
        d = Actions.logFood(d, mon, "Lunch", "rice-white-dry", 2.0, at("2026-11-02T12:10"))
        val m = Diary.totals(d, mon)
        assertEquals(134.0 * 2 + 160.0 * 2, m.kcal, 1e-9)
        assertEquals(25.2 * 2 + 3.0 * 2, m.protein, 1e-9)
        d = Actions.logMeal(d, mon, "Supper", "chicken-rice-bowl", 1.0, at("2026-11-02T19:00"))
        assertEquals(m.kcal * 2 + 55.0 + 119.0 * 0.5, Diary.totals(d, mon).kcal, 1e-6)
        assertEquals(false, Diary.isComplete(d, mon)) // pre-workout missing
        d = Actions.logFood(d, mon, "Pre-workout", "banana", 1.0, at("2026-11-02T16:00"))
        assertTrue(Diary.isComplete(d, mon))
        val copied = Actions.copySlot(d, mon, mon.plusDays(1), "Lunch", at("2026-11-03T12:00"))
        assertEquals(Diary.slotTotals(d, mon, "Lunch"), Diary.slotTotals(copied, mon.plusDays(1), "Lunch"))
        val n = Diary.nutrients(d, mon)
        assertTrue(n.getValue(Nutrient.POTASSIUM).first > 0)
        assertTrue(n.getValue(Nutrient.POTASSIUM).second in 0.0..1.0)
    }

    @Test
    fun `fasting window status`() {
        var d = Actions.logFood(seed, mon.minusDays(1), "Supper", "egg", 2.0, at("2026-11-01T19:30"))
        val morning = Fasting.status(d, at("2026-11-02T09:00"), z)
        assertTrue(!morning.inWindow)
        assertEquals(13.5, morning.hoursSinceFood!!, 1e-9)
        assertEquals(180L, morning.minutesToChange)
        d = Actions.logFood(d, mon, "Lunch", "egg", 2.0, at("2026-11-02T12:30"))
        assertEquals(17.0, Fasting.overnightHours(d, mon)!!, 1e-9)
        val noon = Fasting.status(d, at("2026-11-02T13:00"), z)
        assertTrue(noon.inWindow); assertEquals(0, noon.outsideWindow)
        d = Actions.logFood(d, mon, "Snacks", "banana", 1.0, at("2026-11-02T21:00"))
        assertEquals(1, Fasting.status(d, at("2026-11-02T21:10"), z).outsideWindow)
    }

    @Test
    fun `nags escalate and stay quiet at night`() {
        assertTrue(Nags.due(seed, at("2026-11-02T05:00"), z, true).isEmpty())
        val n = Nags.due(seed, at("2026-11-02T14:00"), z, true)
        assertTrue(n.any { it.kind == NagKind.WEIGH_IN && it.level == 3 }) // 5 h late
        assertTrue(n.any { it.key == "meal:Lunch" && it.level == 2 })    // 60 min late at 45-min steps
        assertTrue(n.none { it.key == "meal:Pre-workout" })
        assertTrue(n.any { it.kind == NagKind.WATER })
        assertTrue(n.any { it.kind == NagKind.CHECK_IN })
        var d = Actions.setWeight(seed, mon, 199.4, at("2026-11-02T07:00"))
        d = Actions.logFood(d, mon, "Lunch", "egg", 1.0, at("2026-11-02T12:00"))
        d = Actions.addWater(d, mon, 64.0, at("2026-11-02T13:00"))
        d = Actions.checkIn(d, mon, 4, 3, at("2026-11-02T13:30"))
        assertTrue(Nags.due(d, at("2026-11-02T14:00"), z, true).isEmpty())
        assertTrue(Nags.due(seed, at("2026-11-02T23:00"), z, true).isEmpty())
    }

    @Test
    fun `adaptive maintenance from weigh-ins and logs`() {
        // 28 days eating ~3000 while the trend drops ~1 lb → maintenance ≈ 3000 + 3500/28 ≈ 3125
        var d = seed
        val start = mon.minusDays(28)
        for (i in 0 until 28) {
            val day = start.plusDays(i.toLong())
            val ts = day.atTime(12, 0).atZone(z).toInstant().toEpochMilli()
            d = Actions.setWeight(d, day, 200.0 - i * (1.0 / 27) + if (i % 2 == 0) 0.6 else -0.6, ts)
            d = Actions.logFood(d, day, "Lunch", "rice-white-dry", 3000.0 / 160, ts)
            d = Actions.setComplete(d, day, true)
        }
        val est = Adaptive.estimate(d, mon)
        assertNotNull(est)
        assertEquals(3000.0, est.intake, 1e-6)
        assertTrue(est.tdee in 3050.0..3200.0, "tdee ${est.tdee}")
        assertEquals("you", Targets.forDay(d, mon, true).maintenanceSource)
        assertNull(Adaptive.estimate(seed, mon))
        val rate = Trend.ratePerWeek(d, mon)!!
        assertTrue(rate < 0 && rate > -0.6, "rate $rate")
    }

    @Test
    fun `more active than usual eats some back`() {
        var d = seed
        for (i in 1..7) d = Actions.putActivity(d, DayActivity(mon.minusDays(i.toLong()).toString(), activeKcal = 600.0))
        d = Actions.putActivity(d, DayActivity(mon.toString(), activeKcal = 1000.0))
        val t = Targets.forDay(d, mon, true)
        assertEquals(400 * 0.7, t.activityKcal, 1e-6)
        assertTrue(t.lines.any { it.contains("More active") })
    }

    @Test
    fun `remedies on a foggy day`() {
        var d = Actions.logFood(seed, mon.minusDays(1), "Supper", "egg", 2.0, at("2026-11-01T19:00"))
        d = Actions.checkIn(d, mon, 2, 2, at("2026-11-02T14:00"))
        val r = Remedies.forToday(d, at("2026-11-02T14:00"), z, Targets.forDay(d, mon, true))
        assertTrue(r.any { it.title.contains("Eat") })
        assertTrue(r.any { it.title.contains("protein", ignoreCase = true) })
        assertTrue(r.any { it.title.contains("water", ignoreCase = true) })
        val fine = Actions.checkIn(d, mon, 4, 4, at("2026-11-02T14:05"))
        assertTrue(Remedies.forToday(fine, at("2026-11-02T14:05"), z, Targets.forDay(fine, mon, true)).isEmpty())
    }

    @Test
    fun `insights compare fasting days`() {
        var d = seed
        for (i in 0 until 14) {
            val day = mon.minusDays(14L - i)
            val long = i % 2 == 0
            val firstMeal = day.atTime(if (long) 13 else 8, 0).atZone(z).toInstant().toEpochMilli()
            d = Actions.logFood(d, day, "Lunch", "egg", 2.0, firstMeal)
            d = Actions.logFood(d, day, "Supper", "egg", 2.0, day.atTime(20, 0).atZone(z).toInstant().toEpochMilli() - 3_600_000)
            d = Actions.checkIn(d, day, if (long) 4 else 2, 3, firstMeal)
        }
        val c = Insights.comparisons(d, z, mon)
        val fast = c.first { it.question == "Fasting" && it.metric == "Clarity" }
        assertTrue(fast.meanA > fast.meanB)
        assertEquals("likely real", fast.verdict)
    }

    @Test
    fun `sync status serializes`() {
        val st = Sync.status(seed, at("2026-11-02T14:00"), z, true)
        val json = DietStore.JSON.encodeToString(DietStatus.serializer(), st)
        assertTrue(json.contains("\"kcalTarget\""))
        assertTrue(st.nags.first().level >= st.nags.last().level)
    }

    @Test
    fun `store round trips with nutrient maps`() {
        val f = java.io.File.createTempFile("dietdata", ".json"); f.delete()
        val st = DietStore(f) { seed }
        st.update { Actions.setWeight(it, mon, 200.2, 1L) }
        val again = DietStore(f) { error("should load") }
        assertEquals(seed.foods.first { it.id == "banana" }.nutrients, again.current.food("banana")!!.nutrients)
        assertEquals(1, again.current.weights.size)
    }
}
