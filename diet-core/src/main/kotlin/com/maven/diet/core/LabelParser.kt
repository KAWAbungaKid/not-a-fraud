package com.maven.diet.core

/**
 * Reads a US Nutrition Facts panel from OCR text (the phone's on-device text recognition). Pulls the serving
 * size, calories, the big three and the rest of the label; nutrients given only as "% DV" are converted with the
 * FDA daily values. Takes the first number after each label word (per-serving column on dual-column labels).
 * Always shown to the user for checking before it's saved.
 */
object LabelParser {
    data class Result(
        val serving: String?,
        val servingGrams: Double?,
        val servingMl: Double?,
        val units: List<FoodUnit>,
        val servingsPerContainer: Double?,
        val kcal: Double?,
        val protein: Double?,
        val carbs: Double?,
        val fat: Double?,
        val nutrients: Map<Nutrient, Double>,
    ) {
        /** Of the four required fields, which ones were read. */
        val missing: List<String> get() = listOfNotNull(
            if (kcal == null) "calories" else null, if (protein == null) "protein" else null,
            if (carbs == null) "carbs" else null, if (fat == null) "fat" else null,
        )
        val found: Int get() = listOfNotNull(kcal, protein, carbs, fat).size + nutrients.size + (if (serving != null) 1 else 0)
    }

    /** FDA daily values (2016+ labels) for converting "%" lines. */
    private val DV = mapOf(
        Nutrient.CALCIUM to 1300.0, Nutrient.IRON to 18.0, Nutrient.POTASSIUM to 4700.0,
        Nutrient.VITAMIN_D to 20.0, Nutrient.MAGNESIUM to 420.0, Nutrient.SODIUM to 2300.0,
        Nutrient.FIBER to 28.0, Nutrient.SAT_FAT to 20.0, Nutrient.CHOLESTEROL to 300.0,
    )

    private const val V = """([0-9oOlI]+(?:[.,][0-9oO]+)?)"""
    private val RO = setOf(RegexOption.IGNORE_CASE)

    /** OCR often reads 0 as O and 1 as l / I inside numbers. */
    private fun num(s: String): Double? =
        s.replace('o', '0').replace('O', '0').replace('l', '1').replace('I', '1').replace(',', '.').toDoubleOrNull()

    private fun grams(text: String, label: String): Double? =
        Regex("""$label[^0-9a-z]{0,6}$V\s*g(?![a-z])""", RO).find(text)?.let { num(it.groupValues[1]) }

    /** mg / mcg value, or a % converted with the daily value. */
    private fun amount(text: String, label: String, unit: String, n: Nutrient): Double? {
        val m = Regex("""$label[^0-9a-z]{0,8}$V\s*($unit|%)""", RO).find(text) ?: return null
        val v = num(m.groupValues[1]) ?: return null
        return if (m.groupValues[2] == "%") DV[n]?.let { v / 100 * it } else v
    }

    fun parse(raw: String): Result {
        val text = raw.replace('\r', '\n').replace("µg", "mcg").replace(Regex("(?<=\\d)\\s*ug\\b", RegexOption.IGNORE_CASE), "mcg")
        val kcal = Regex("""calories(?!\s*from)[^0-9]{0,14}([0-9oO]{1,4})(?![0-9]*[ \t]*%)""", RO).find(text)?.let { num(it.groupValues[1]) }
        val fat = grams(text, """total\s*fat""") ?: grams(text, """(?<!saturated |trans )\bfat""")
        val carbs = grams(text, """total\s*carb\w*\.?""") ?: grams(text, """carbohydrates?""")
        val protein = grams(text, """protein""")

        val n = LinkedHashMap<Nutrient, Double>()
        grams(text, """saturated\s*fat""")?.let { n[Nutrient.SAT_FAT] = it }
        (grams(text, """dietary\s*fiber""") ?: grams(text, """fiber"""))?.let { n[Nutrient.FIBER] = it }
        (grams(text, """total\s*sugars?""") ?: grams(text, """(?<!added )\bsugars?"""))?.let { n[Nutrient.SUGAR] = it }
        amount(text, """cholesterol""", "mg", Nutrient.CHOLESTEROL)?.let { n[Nutrient.CHOLESTEROL] = it }
        amount(text, """sodium""", "mg", Nutrient.SODIUM)?.let { n[Nutrient.SODIUM] = it }
        amount(text, """potassium""", "mg", Nutrient.POTASSIUM)?.let { n[Nutrient.POTASSIUM] = it }
        amount(text, """calcium""", "mg", Nutrient.CALCIUM)?.let { n[Nutrient.CALCIUM] = it }
        amount(text, """\biron""", "mg", Nutrient.IRON)?.let { n[Nutrient.IRON] = it }
        amount(text, """magnesium""", "mg", Nutrient.MAGNESIUM)?.let { n[Nutrient.MAGNESIUM] = it }
        val vitD = Regex("""vit(?:amin)?\.?\s*d[^0-9a-z]{0,8}$V\s*(mcg|iu|%)""", RO).find(text)
        vitD?.let { m ->
            num(m.groupValues[1])?.let { v ->
                n[Nutrient.VITAMIN_D] = when (m.groupValues[2].lowercase()) { "iu" -> v / 40; "%" -> v / 100 * 20; else -> v }
            }
        }

        val serving = Regex("""serving\s*size[:\s]*([^\n]{1,40})""", RO).find(text)?.groupValues?.get(1)
        val servingClean = serving?.trim()?.trimEnd('.', ',', ':')?.takeIf { it.any(Char::isDigit) }
        val parsed = servingClean?.let { Units.parseServing(it) }
        val perContainer = Regex("""$V\s*servings?\s*per\s*container""", RO).find(text)?.let { num(it.groupValues[1]) }
            ?: Regex("""servings?\s*per\s*container[^0-9]{0,10}$V""", RO).find(text)?.let { num(it.groupValues[1]) }

        return Result(
            serving = servingClean, servingGrams = parsed?.grams, servingMl = parsed?.ml, units = parsed?.counts ?: emptyList(),
            servingsPerContainer = perContainer,
            kcal = kcal ?: if (protein != null && carbs != null && fat != null) protein * 4 + carbs * 4 + fat * 9 else null,
            protein = protein, carbs = carbs, fat = fat, nutrients = n,
        )
    }
}
