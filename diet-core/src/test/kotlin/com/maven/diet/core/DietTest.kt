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

class UnitsAndLabelTest {
    @Test
    fun `serving text gives grams, ml and count units`() {
        val p = Units.parseServing("2/3 cup (55g)")
        assertEquals(55.0, p.grams); assertEquals(236.588236 * 2 / 3, p.ml!!, 1e-6); assertTrue(p.counts.isEmpty())
        assertEquals(112.0, Units.parseServing("4 oz (112 g)").grams)           // exact grams beat converted oz
        assertEquals(240.0, Units.parseServing("1 cup (240 mL)").ml)
        assertEquals(FoodUnit("egg", 0.5), Units.parseServing("2 eggs (100 g)").counts.single())
        assertEquals(FoodUnit("slice", 1.0), Units.parseServing("1 slice (32 g)").counts.single())
        assertEquals(1.5 * 236.588236, Units.parseServing("1 1/2 cups").ml!!, 1e-6)
        assertEquals(240.0, Units.parseServing("8 fl oz (240mL)").ml)
        assertEquals(29.5735296 * 8, Units.parseServing("8 fl oz").ml!!, 1e-6)
    }

    @Test
    fun `log chicken in ounces and rice in cups`() {
        val seed = Seed.data("2026-11-03")
        val chicken = seed.food("chicken-breast-raw")!!     // 4 oz (112 g)
        val names = Units.options(chicken).map { it.name }
        assertTrue(names.containsAll(listOf("serving", "g", "oz", "lb", "kg")))
        assertEquals(2.0 * 28.349523125 * 6 / 112.0 / 2, Units.toServings(chicken, 6.0, "oz")!!, 1e-9)
        val rice = seed.food("rice-white-dry")!!            // 1/4 cup (45 g)
        assertEquals(4.0, Units.toServings(rice, 1.0, "cup")!!, 1e-6)
        assertTrue("tbsp" in Units.options(rice).map { it.name })
        val egg = seed.food("egg")!!
        assertEquals(3.0, Units.toServings(egg, 3.0, "egg")!!, 1e-9)
        assertEquals("egg", Units.default(egg).name)
        val d = Actions.logAmount(seed, java.time.LocalDate.parse("2026-11-03"), "Lunch", "chicken-breast-raw", 8.0, "oz", 1L)
        val e = d.log.single()
        assertEquals(8.0 * 28.349523125 / 112.0, e.servings, 1e-9)
        assertEquals("8 oz", Units.describe(chicken, e.servings, e.amount, e.unit))
        assertEquals("3 eggs", Units.label(3.0, "egg"))
    }

    @Test
    fun `migration fills units for old data`() {
        val old = DietData(version = 1, foods = listOf(Food("x", "Oats", serving = "1/2 cup (40 g)", kcal = 150.0, protein = 5.0, carbs = 27.0, fat = 3.0)))
        val m = Migration.apply(old)
        assertEquals(2, m.version)
        assertNotNull(m.foods.single().servingMl)
    }

    private val modern = """
        Nutrition Facts
        8 servings per container
        Serving size 2/3 cup (55g)
        Amount per serving
        Calories 230
        % Daily Value*
        Total Fat 8g 10%
        Saturated Fat 1g 5%
        Trans Fat 0g
        Cholesterol Omg 0%
        Sodium 160mg 7%
        Total Carbohydrate 37g 13%
        Dietary Fiber 4g 14%
        Total Sugars 12g
        Includes 10g Added Sugars 20%
        Protein 3g
        Vitamin D 2mcg 10%
        Calcium 260mg 20%
        Iron 8mg 45%
        Potassium 240mg 6%
    """.trimIndent()

    @Test
    fun `reads a modern label`() {
        val r = LabelParser.parse(modern)
        assertEquals("2/3 cup (55g)", r.serving)
        assertEquals(55.0, r.servingGrams)
        assertEquals(8.0, r.servingsPerContainer)
        assertEquals(230.0, r.kcal); assertEquals(8.0, r.fat); assertEquals(37.0, r.carbs); assertEquals(3.0, r.protein)
        assertEquals(1.0, r.nutrients[Nutrient.SAT_FAT]); assertEquals(0.0, r.nutrients[Nutrient.CHOLESTEROL])
        assertEquals(160.0, r.nutrients[Nutrient.SODIUM]); assertEquals(4.0, r.nutrients[Nutrient.FIBER])
        assertEquals(12.0, r.nutrients[Nutrient.SUGAR]); assertEquals(2.0, r.nutrients[Nutrient.VITAMIN_D])
        assertEquals(260.0, r.nutrients[Nutrient.CALCIUM]); assertEquals(8.0, r.nutrients[Nutrient.IRON])
        assertEquals(240.0, r.nutrients[Nutrient.POTASSIUM])
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun `reads an older split-column label`() {
        val r = LabelParser.parse("""
            Nutrition Facts
            Serving Size 1 cup (240mL)
            Servings Per Container about 4
            Amount Per Serving
            Calories 130 Calories from Fat 45
            Total Fat 5g
            8%
            Sodium 125mg
            Total Carbohydrate
            12g
            Protein
            8g
            Vitamin A 10% • Vitamin C 4%
            Calcium 30% • Iron 0%
            Vitamin D 25%
        """.trimIndent())
        assertEquals(240.0, r.servingMl)
        assertEquals(4.0, r.servingsPerContainer)
        assertEquals(130.0, r.kcal); assertEquals(5.0, r.fat); assertEquals(12.0, r.carbs); assertEquals(8.0, r.protein)
        assertEquals(125.0, r.nutrients[Nutrient.SODIUM])
        assertEquals(390.0, r.nutrients[Nutrient.CALCIUM]!!, 1e-9)
        assertEquals(0.0, r.nutrients[Nutrient.IRON])
        assertEquals(5.0, r.nutrients[Nutrient.VITAMIN_D]!!, 1e-9)
    }

    @Test
    fun `partial read reports what is missing`() {
        val r = LabelParser.parse("Calories 90\nProtein 20g")
        assertEquals(listOf("carbs", "fat"), r.missing)
    }

    @Test
    fun `meal slot edits from Not a Fraud`() {
        val s = Settings()
        assertEquals("16:05", MealSync.apply(s, "Pre-workout", "Pre-workout", "16:05", false).slots.first { it.name == "Pre-workout" }.by)
        val added = MealSync.apply(s, null, "Breakfast", "7:30", false)
        assertEquals("07:30", added.slots.first { it.name == "Breakfast" }.by)
        val renamed = MealSync.apply(s, "Supper", "Dinner", "19:00", false)
        assertTrue(renamed.slots.none { it.name == "Supper" } && renamed.slots.any { it.name == "Dinner" && it.by == "19:00" })
        assertTrue(MealSync.apply(s, "Lunch", null, null, true).slots.none { it.name == "Lunch" })
    }
}
