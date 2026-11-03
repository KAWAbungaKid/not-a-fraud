package com.maven.diet.core

/** One way to measure a food, and how many servings one of it is. */
data class UnitOption(val name: String, val servingsPerUnit: Double, val kind: Kind) {
    enum class Kind { SERVING, COUNT, MASS, VOLUME }
}

/**
 * Measurement units. Every food has "serving" and its count units ("egg", "slice", "scoop"); grams in a
 * serving unlock g / oz / lb / kg; millilitres unlock ml / tsp / tbsp / fl oz / cup / pint / l. A serving
 * text like "2/3 cup (55 g)" fills both. Everything is stored as servings; the unit is kept for display.
 */
object Units {
    val MASS = linkedMapOf("g" to 1.0, "oz" to 28.349523125, "lb" to 453.59237, "kg" to 1000.0)
    val VOLUME = linkedMapOf(
        "ml" to 1.0, "tsp" to 4.92892159, "tbsp" to 14.7867648, "fl oz" to 29.5735296,
        "cup" to 236.588236, "pint" to 473.176473, "l" to 1000.0,
    )
    const val SERVING = "serving"

    fun options(f: Food): List<UnitOption> = buildList {
        add(UnitOption(SERVING, 1.0, UnitOption.Kind.SERVING))
        for (u in f.units) if (u.servings > 0) add(UnitOption(u.name, u.servings, UnitOption.Kind.COUNT))
        f.servingGrams?.takeIf { it > 0 }?.let { g -> for ((n, k) in MASS) add(UnitOption(n, k / g, UnitOption.Kind.MASS)) }
        f.servingMl?.takeIf { it > 0 }?.let { ml -> for ((n, k) in VOLUME) add(UnitOption(n, k / ml, UnitOption.Kind.VOLUME)) }
    }

    fun option(f: Food, unit: String?): UnitOption? = options(f).firstOrNull { it.name == (unit ?: SERVING) }

    fun toServings(f: Food, amount: Double, unit: String?): Double? = option(f, unit)?.let { amount * it.servingsPerUnit }

    /** The most natural unit for this food: its count unit, else grams, else servings. */
    fun default(f: Food): UnitOption = options(f).let { o ->
        o.firstOrNull { it.kind == UnitOption.Kind.COUNT } ?: o.firstOrNull { it.name == SERVING }!!
    }

    fun label(amount: Double, unit: String): String {
        val plural = amount != 1.0 && unit !in MASS && unit !in VOLUME && !unit.endsWith("s")
        return "${Num.fmt(amount)} $unit${if (plural) "s" else ""}"
    }

    /** "6 oz", "1.5 servings" for a logged entry. */
    fun describe(f: Food?, servings: Double, amount: Double?, unit: String?): String =
        if (amount != null && unit != null && unit != SERVING) label(amount, unit)
        else "${Num.fmt(servings)} × ${f?.serving ?: "serving"}"

    data class ParsedServing(val grams: Double?, val ml: Double?, val counts: List<FoodUnit>)

    private val QTY = """(\d+\s+\d+/\d+|\d+/\d+|\d+(?:\.\d+)?|\.\d+)"""
    private val TOKEN = Regex("""$QTY\s*(fl\.?\s*oz|fluid ounces?|[a-zA-Z]+)""")
    private val MASS_ALIASES = mapOf(
        "g" to "g", "gr" to "g", "gram" to "g", "grams" to "g", "oz" to "oz", "ounce" to "oz", "ounces" to "oz",
        "lb" to "lb", "lbs" to "lb", "pound" to "lb", "pounds" to "lb", "kg" to "kg",
    )
    private val VOLUME_ALIASES = mapOf(
        "ml" to "ml", "milliliter" to "ml", "milliliters" to "ml", "millilitre" to "ml", "l" to "l", "liter" to "l", "litre" to "l",
        "tsp" to "tsp", "teaspoon" to "tsp", "teaspoons" to "tsp", "tbsp" to "tbsp", "tbs" to "tbsp", "tablespoon" to "tbsp",
        "tablespoons" to "tbsp", "cup" to "cup", "cups" to "cup", "c" to "cup", "pint" to "pint", "pints" to "pint",
    )
    private val NOT_UNITS = setOf("mg", "mcg", "kcal", "cal", "calories", "percent", "x", "per", "about", "servings")

    fun qty(s: String): Double? {
        val t = s.trim()
        val mixed = Regex("""(\d+)\s+(\d+)/(\d+)""").matchEntire(t)
        if (mixed != null) return mixed.groupValues[1].toDouble() + mixed.groupValues[2].toDouble() / mixed.groupValues[3].toDouble()
        val frac = Regex("""(\d+)/(\d+)""").matchEntire(t)
        if (frac != null) return frac.groupValues[1].toDouble() / frac.groupValues[2].toDouble().coerceAtLeast(1.0)
        return t.toDoubleOrNull()
    }

    fun parseServing(text: String): ParsedServing {
        var grams: Double? = null; var ml: Double? = null
        var gramsExact = false; var mlExact = false
        val counts = ArrayList<FoodUnit>()
        for (m in TOKEN.findAll(text)) {
            val q = qty(m.groupValues[1]) ?: continue
            if (q <= 0) continue
            val raw = m.groupValues[2].lowercase().replace(".", "").replace(Regex("\\s+"), " ")
            when {
                raw.startsWith("fl") || raw.startsWith("fluid") -> if (ml == null) ml = q * VOLUME.getValue("fl oz")
                // An exact "(55 g)" / "(240 ml)" beats a converted "2 oz" / "1 cup".
                raw in MASS_ALIASES -> {
                    val unit = MASS_ALIASES.getValue(raw)
                    if (grams == null || (unit == "g" && !gramsExact)) { grams = q * MASS.getValue(unit); gramsExact = unit == "g" }
                }
                raw in VOLUME_ALIASES -> {
                    val unit = VOLUME_ALIASES.getValue(raw)
                    if (ml == null || (unit == "ml" && !mlExact)) { ml = q * VOLUME.getValue(unit); mlExact = unit == "ml" }
                }
                raw in NOT_UNITS || raw.length < 2 -> {}
                else -> {
                    val name = singular(raw)
                    if (counts.none { it.name == name }) counts += FoodUnit(name, 1.0 / q)
                }
            }
        }
        return ParsedServing(grams, ml, counts)
    }

    private fun singular(w: String) = when {
        w.endsWith("ies") -> w.dropLast(3) + "y"
        w.endsWith("ches") || w.endsWith("shes") || w.endsWith("oes") -> w.dropLast(2)
        w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        else -> w
    }

    /** Fill grams / ml / count units from the serving text where they're missing. */
    fun enrich(f: Food): Food {
        val p = parseServing(f.serving)
        return f.copy(
            servingGrams = f.servingGrams ?: p.grams,
            servingMl = f.servingMl ?: p.ml,
            units = f.units.ifEmpty { p.counts },
        )
    }
}
