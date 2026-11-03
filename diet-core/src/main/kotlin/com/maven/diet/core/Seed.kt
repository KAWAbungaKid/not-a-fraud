package com.maven.diet.core

import com.maven.diet.core.Nutrient.*

/**
 * Starter food list: staples Maven eats (chicken and rice first) with approximate values from USDA FoodData
 * Central / typical labels. They're marked "builtin" — when you have the actual package, copy the label into a
 * new food and archive the builtin one. Micronutrients are only filled where well known; the real USDA dataset
 * comes in a later version.
 */
object Seed {
    private fun f(
        id: String, name: String, serving: String, grams: Double?, kcal: Double, p: Double, c: Double, fat: Double,
        vararg n: Pair<Nutrient, Double>,
    ) = Units.enrich(Food(id, name, "", serving, grams, kcal = kcal, protein = p, carbs = c, fat = fat, nutrients = n.toMap(), source = "builtin"))

    val foods = listOf(
        f("chicken-breast-raw", "Chicken breast, raw", "4 oz (112 g)", 112.0, 134.0, 25.2, 0.0, 2.9, SODIUM to 50.0, POTASSIUM to 375.0),
        f("chicken-breast-cooked", "Chicken breast, cooked", "100 g", 100.0, 165.0, 31.0, 0.0, 3.6, SODIUM to 74.0, POTASSIUM to 256.0),
        f("chicken-thigh-raw", "Chicken thigh, boneless skinless, raw", "4 oz (112 g)", 112.0, 136.0, 22.0, 0.0, 4.6, SODIUM to 95.0),
        f("ground-beef-90", "Ground beef 90/10, raw", "4 oz (112 g)", 112.0, 200.0, 22.6, 0.0, 11.2, IRON to 2.6, SAT_FAT to 4.5),
        f("egg", "Egg, large", "1 egg (50 g)", 50.0, 72.0, 6.3, 0.4, 4.8, CHOLESTEROL to 186.0, VITAMIN_D to 1.1),
        f("egg-whites", "Liquid egg whites", "3 tbsp (46 g)", 46.0, 25.0, 5.0, 0.0, 0.0, SODIUM to 75.0),
        f("rice-white-dry", "White rice, dry", "1/4 cup (45 g)", 45.0, 160.0, 3.0, 36.0, 0.0),
        f("rice-white-cooked", "White rice, cooked", "1 cup (158 g)", 158.0, 205.0, 4.3, 44.5, 0.4, FIBER to 0.6),
        f("rice-brown-cooked", "Brown rice, cooked", "1 cup (195 g)", 195.0, 218.0, 4.5, 45.8, 1.6, FIBER to 3.5, MAGNESIUM to 84.0),
        f("oats", "Rolled oats, dry", "1/2 cup (40 g)", 40.0, 150.0, 5.0, 27.0, 3.0, FIBER to 4.0, IRON to 1.7),
        f("potato", "Potato, baked, medium", "1 medium (173 g)", 173.0, 161.0, 4.3, 36.6, 0.2, FIBER to 3.8, POTASSIUM to 926.0),
        f("sweet-potato", "Sweet potato, baked, medium", "1 medium (114 g)", 114.0, 103.0, 2.3, 23.6, 0.2, FIBER to 3.8, POTASSIUM to 542.0),
        f("tortilla", "Flour tortilla, 8 in", "1 tortilla (49 g)", 49.0, 146.0, 3.9, 24.6, 3.6),
        f("bread-wheat", "Whole wheat bread", "1 slice (32 g)", 32.0, 80.0, 4.0, 14.0, 1.0, FIBER to 2.0),
        f("black-beans", "Black beans, canned, drained", "1/2 cup (120 g)", 120.0, 110.0, 7.0, 20.0, 0.5, FIBER to 7.0),
        f("broccoli", "Broccoli, cooked", "1 cup (156 g)", 156.0, 55.0, 3.7, 11.2, 0.6, FIBER to 5.1, POTASSIUM to 457.0),
        f("mixed-veg", "Frozen mixed vegetables, cooked", "1 cup (182 g)", 182.0, 118.0, 5.2, 23.8, 0.3, FIBER to 7.6),
        f("banana", "Banana, medium", "1 medium (118 g)", 118.0, 105.0, 1.3, 27.0, 0.4, FIBER to 3.1, POTASSIUM to 422.0),
        f("apple", "Apple, medium", "1 medium (182 g)", 182.0, 95.0, 0.5, 25.0, 0.3, FIBER to 4.4),
        f("milk-2", "Milk, 2%", "1 cup (244 g)", 244.0, 122.0, 8.1, 11.7, 4.8, CALCIUM to 293.0, VITAMIN_D to 2.9),
        f("milk-whole", "Milk, whole", "1 cup (244 g)", 244.0, 149.0, 7.7, 11.7, 7.9, CALCIUM to 276.0, VITAMIN_D to 3.2),
        f("choc-milk", "Chocolate milk, low-fat", "1 cup (250 g)", 250.0, 180.0, 8.0, 28.0, 2.5, CALCIUM to 290.0, SUGAR to 26.0),
        f("greek-yogurt", "Greek yogurt, plain nonfat", "170 g", 170.0, 100.0, 17.0, 6.0, 0.7, CALCIUM to 187.0),
        f("cheddar", "Cheddar cheese", "1 oz (28 g)", 28.0, 114.0, 7.0, 0.4, 9.4, CALCIUM to 200.0, SAT_FAT to 5.3),
        f("whey", "Whey protein (generic)", "1 scoop (31 g)", 31.0, 120.0, 24.0, 3.0, 1.5),
        f("peanut-butter", "Peanut butter", "2 tbsp (32 g)", 32.0, 190.0, 7.0, 7.0, 16.0, FIBER to 2.0),
        f("olive-oil", "Olive oil", "1 tbsp (13.5 g)", 13.5, 119.0, 0.0, 0.0, 13.5),
        f("butter", "Butter", "1 tbsp (14 g)", 14.0, 102.0, 0.1, 0.0, 11.5, SAT_FAT to 7.3),
        f("creatine", "Creatine monohydrate", "5 g", 5.0, 0.0, 0.0, 0.0, 0.0),
    )

    val meals = listOf(
        SavedMeal(
            "chicken-rice-bowl", "Chicken & rice bowl (example)",
            listOf(MealItem("chicken-breast-raw", 2.0), MealItem("rice-white-dry", 2.0), MealItem("broccoli", 1.0), MealItem("olive-oil", 0.5)),
            servings = 1.0,
            instructions = "Example — edit to match what you actually make. 8 oz raw chicken, 1/2 cup dry rice, a cup of broccoli, half a tbsp oil.",
        ),
    )

    fun data(today: String): DietData = DietData(foods = foods, meals = meals)
}

object Migration {
    const val CURRENT = 2
    fun apply(d: DietData): DietData {
        var x = d
        // v2: measurement units — fill grams / ml / count units from each food's serving text.
        if (x.version < 2) x = x.copy(foods = x.foods.map { Units.enrich(it) }, version = 2)
        return x
    }
}
