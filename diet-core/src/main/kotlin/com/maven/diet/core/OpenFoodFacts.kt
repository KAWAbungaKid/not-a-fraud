package com.maven.diet.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Barcode → food (§ 5 step 2): reads an Open Food Facts product
 * (https://world.openfoodfacts.org/api/v2/product/<barcode>.json) into a Food the user checks before saving.
 * Per-serving values when the product has them, otherwise per 100 g.
 */
object OpenFoodFacts {
    fun url(barcode: String) = "https://world.openfoodfacts.org/api/v2/product/${barcode.filter { it.isDigit() }}.json" +
        "?fields=product_name,brands,serving_size,serving_quantity,nutriments"

    data class Parsed(val food: Food, val perServing: Boolean, val missing: List<String>)

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String, barcode: String, id: String): Parsed? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        if (root["status"]?.jsonPrimitive?.contentOrNull?.let { it == "0" || it == "failure" } == true) return null
        val p = root["product"]?.jsonObject ?: return null
        val n = p["nutriments"]?.jsonObject ?: JsonObject(emptyMap())
        fun num(key: String) = n[key]?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        val servingQty = p["serving_quantity"]?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        val perServing = num("energy-kcal_serving") != null
        fun v(key: String): Double? = if (perServing) num("${key}_serving") else num("${key}_100g")
        val kcal = v("energy-kcal") ?: v("energy")?.let { it / 4.184 }
        val missing = listOfNotNull("calories".takeIf { kcal == null }, "protein".takeIf { v("proteins") == null },
            "carbs".takeIf { v("carbohydrates") == null }, "fat".takeIf { v("fat") == null })
        val nutrients = buildMap {
            v("fiber")?.let { put(Nutrient.FIBER, it) }
            v("sugars")?.let { put(Nutrient.SUGAR, it) }
            v("saturated-fat")?.let { put(Nutrient.SAT_FAT, it) }
            v("sodium")?.let { put(Nutrient.SODIUM, it * 1000) }
            v("potassium")?.let { put(Nutrient.POTASSIUM, it * 1000) }
            v("calcium")?.let { put(Nutrient.CALCIUM, it * 1000) }
            v("iron")?.let { put(Nutrient.IRON, it * 1000) }
            v("cholesterol")?.let { put(Nutrient.CHOLESTEROL, it * 1000) }
        }
        val serving = if (perServing) p["serving_size"]?.jsonPrimitive?.contentOrNull ?: "1 serving" else "100 g"
        val food = Units.enrich(Food(
            id = id,
            name = p["product_name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
            brand = p["brands"]?.jsonPrimitive?.contentOrNull.orEmpty().split(',').first().trim(),
            serving = serving,
            servingGrams = if (perServing) servingQty else 100.0,
            kcal = kcal ?: 0.0, protein = v("proteins") ?: 0.0, carbs = v("carbohydrates") ?: 0.0, fat = v("fat") ?: 0.0,
            nutrients = nutrients, source = "label", barcode = barcode,
        ))
        return Parsed(food, perServing, missing)
    }
}
