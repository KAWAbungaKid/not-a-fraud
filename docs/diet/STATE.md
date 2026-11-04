# Not a Twig (diet app) — state of the build

Living doc for Maven's diet app. Read this first in any chat about it. The other apps: Not a Fraud
(`docs/STATE.md`), Not a Troglodyte (workout, `docs/workout/STATE.md`). Last updated 2026-11-03 (v0.2.1).

- **Lives in:** the Not a Fraud repo, modules `diet-core/` (pure Kotlin) + `diet/` (Android). Same signing key.
- **App ID** `com.maven.diet` (label "Not a Twig"); debug `com.maven.diet.dev` ("Twig dev").
- **Version** `dietVersionName` / `dietVersionCode` in `gradle.properties`.
- **CI:** `.github/workflows/diet-ci.yml` (core tests, debug APK, emulator smoke test + screenshots on
  `ci-logs/diet-shots`). **Release:** bump version, add `docs/diet/releases/vX.Y.Z.md`, push →
  `diet-release.yml` publishes GitHub Release `diet-vX.Y.Z` (not Latest), APK mirrored to `ci-logs/diet-dist`.

## 1. What Maven asked for (2026-11-02)

Modeled on **MyFitnessPal** (diary) and **Eat This Much** (meal plans with recipes). Macro and micronutrient
plan. Food data mostly typed from labels when something new appears — he eats consistently (chicken and rice,
chicken is the main meat), cooks, likes cheap. Sees activity from the gym (workout app) and Samsung Health.
**Weight typed daily.** Suggests eating more when more active, less when not. Suggests foods/recipes with a
breakdown. **Nags like Not a Fraud** when he hasn't eaten / drunk water. Long-term: **a case study on himself** —
his own metabolism vs the average, carb timing (e.g. chocolate milk 15 min vs 3 h before the gym), what he
digests well — and, with the other apps, **bad-day help** (brain fog / low → basics like daylight, protein…).
Keep him honest and consistent.

**His facts / choices:** 6'0", 200 lb, 21, male, can see abs (~12–15 % → 13.5 % used), goal **body recomp**.
Wants to **try intermittent fasting** and have it correlated with energy. Cares about **brain fog** and
**energy for gym / cardio** — everything else he has down. No blood work yet. Types weight in (no smart scale).
Samsung Health has calories burned. Foods and recipes come later. Apps are all "Not a _".
Climbing / sports do not count toward the workout minimum but **do count as cardio** (Not a Fraud side).

## 2. Design rules

- **Logging must be near-zero effort** (the stats spreadsheet died of manual entry): recent foods first, saved
  meals, "Same as yesterday" per meal, a label typed once, 2-tap check-in, 1 number for weight, water buttons,
  "+16 oz" on the notification.
- **Nothing is a black box:** "Where these numbers come from" lists every term of today's target.
- **Honest data beats perfect days:** food outside the fasting window is logged and counted, not hidden.
- **Correlation is labelled as such**; real answers come from experiments (§ 5).

## 3. How it works (v0.1.0)

**Data** (`diet-core/.../Model.kt`, one JSON file `filesDir/diet.json`): profile, foods (per serving: kcal, P/C/F,
optional nutrients map, `source` builtin|label), saved meals / recipes (items + portions), log entries (date,
slot, food, servings, time), water (oz), weights (lb, one per day), check-ins (clarity 1–5, energy 1–5),
activity per day (active kcal + steps from Health Connect; lift minutes + start from the workout app),
complete days, training-day overrides, settings.

**Targets** (`Targets.forDay`):
- Maintenance = BMR × activity factor until real data exists. BMR = Katch-McArdle when body fat is set
  (370 + 21.6 × lean kg → **2065** for Maven), else Mifflin-St Jeor (1950). Factor 1.45 → **≈2994**.
- **Adaptive maintenance** (`Adaptive`): over the last 28 days, average intake on complete days − trend change ×
  3500 / days. Needs 14+ days spanned, 10+ weigh-ins, 10+ complete days; blends in from 25 % and reaches 100 % at
  27 days. This is the "is my metabolism faster" answer.
- \+ goal (recomp 0; cut −0.75, lean bulk +0.25, bulk +0.5 lb/wk × 3500/7).
- \+/− **150 kcal** training vs rest day (as carbs). Training = set by hand, else trained/training today in Not a
  Troglodyte, else "the week still needs workouts" (he's aiming for 6).
- \+ **eat-back:** active kcal today above the 14-day average × 70 %, max 800.
- Protein **0.95 g/lb**, fat **0.35 g/lb** of trend weight, carbs the rest. Day 1 training: ~3144 kcal,
  190 P / 70 F / ~438 C. Water 100 oz + 20 on training days.

**Weight trend** (`Trend`): exponentially smoothed (α 0.1) daily weigh-ins; rate per week over 14 days.

**Fasting** (`Fasting`): eating window (default 12:00–20:00, 16:8), in/out of window, time since food,
overnight fast length per day, items logged outside the window.

**Nags** (`Nags`, every 30 min by alarm, quiet 06:00–22:00): weigh-in after 09:00; each meal slot with a time
(Lunch 13:00, Pre-workout 16:30, Supper 19:30; Snacks none) if empty; water behind a straight line from wake to
1 h before bed by 24+ oz; check-in after 14:00. Level 1→3 every 45 min; level 3 uses the loud channel.

**Bad-day remedies** (`Remedies`): when today's check-in has clarity or energy ≤ 2 — eat if 5 h+ since food in
the window, protein if behind, water if behind, daylight walk, move (walk/gym; short mode in the workout app).

**Insights** (`Insights`): a row per day (kcal, protein, carbs, overnight fast, first-meal hour, water, lifted,
active kcal, clarity, energy) and two-group comparisons for clarity and energy: fasted 16 h+ vs shorter, first
meal before noon vs later, higher vs lower protein, 80+ oz water vs less, lifted vs not. Shown only with 5+ days
per group, with a rough strength (difference / standard error → "likely real / maybe / no clear difference").

**Food data:** 29 built-in staples (approximate USDA / typical label values, flagged "approximate") + an example
chicken & rice bowl recipe. Label entry: name, brand, serving as printed, grams, kcal, P/C/F, and optional fiber,
sugar, sat fat, sodium, potassium, calcium, iron, vitamin D, magnesium, cholesterol; warns when 4P+4C+9F is off
the label's kcal by 15 %+. Micronutrient view shows totals vs daily targets and how much of the day's food lists
each nutrient.

**Screens:** Today (date ‹ ›, overdue list, kcal + macro bars, training/rest chips, the math, fasting card,
check-in + remedies, water, weight, meal slots with + Add / Same as yesterday / Save as meal, "That's everything
I ate", nutrients) · Add food (search, meals, recent, all; servings or grams) · Foods (library, label editor,
meals/recipes editor with per-portion macros) · Progress (weight chart, rate vs goal, maintenance learning,
7-day averages, comparisons, check-in history) · Settings (profile, goal, macros, fasting window, meal slots and
times, water, nag times, Health Connect + workout link status, backup).

### v0.2.0 (2026-11-03): units + label scan

- **Units** (`Units.kt`): every food has `serving`, its count units (`Food.units`: name → servings, e.g. egg,
  slice, scoop), mass units when `servingGrams` is known (g, oz, lb, kg) and volume units when `servingMl` is
  known (ml, tsp, tbsp, fl oz, cup, pint, l). `Units.parseServing("2/3 cup (55g)")` fills grams / ml / count
  units from serving text (exact "(55 g)"/"(240 ml)" beat converted ones); `Units.enrich` runs on save and in
  migration v2. Log entries and meal items keep servings for the math plus `amount` + `unit` for display; the
  amount dialog opens in the unit last used for that food.
- **Label scan** (`LabelParser.kt` + `ui/LabelScan.kt`): camera (`TakePicture` into `cache/labels` via
  FileProvider) or photo picker → ML Kit text recognition (bundled Latin model, offline) → lines regrouped into
  rows by position → parser: serving size, servings per container, calories, total fat, sat fat, cholesterol,
  sodium, carbs, fiber, total sugars, protein, vitamin D (mcg / IU / %), calcium, iron, potassium, magnesium; "%"
  values converted with FDA daily values; OCR O→0, l→1 fixed inside numbers. Fills the food form and says what
  it couldn't find; nothing saves without the user checking. Name isn't on labels — typed by hand.
- Not yet: barcode lookup (Open Food Facts) — would also fill the name/brand.

### v0.2.1 (2026-11-03): Not a Fraud shows the reminders

- `Settings.notifyHere` (Settings → Nagging → "Show reminders here"): off = Twig posts no notifications but
  `Nags.due` still fills `DietStatus.nags`, which Not a Fraud 0.9.0 turns into its routine nags (`nags` off
  empties the list for both). Not a Fraud ticks linked meal routine items from `slotsLogged`, shows the
  check-in in "How's today" and uses clarity / energy ≤ 2 as a burnout signal (Not a Fraud STATE § 21).

## 4. Links between the apps

- **Reads Not a Troglodyte** (`content://com.maven.workout.sync/status`, `/sessions?since=`): training day, lift
  minutes and start time (for carb-timing later). Troglodyte now broadcasts `com.maven.workout.CHANGED` to Twig too.
- **Reads Health Connect:** active calories + steps, last 15 days, on app open.
- **Serves Not a Fraud:** `content://com.maven.diet.sync/status` → JSON `DietStatus`: kcal / protein / carbs /
  fat eaten and targets, water oz and target, trainingDay, weighedToday, trendLb, ratePerWeekLb, fasting,
  inEatingWindow, hoursSinceFood, slotsLogged, **nags** (kind MEAL / WATER / WEIGH_IN / CHECK_IN, key, title, text,
  level 1–3, loudest first), clarity, energy. Broadcast `com.maven.diet.CHANGED` to Not a Fraud on changes.
  Same signature permission `com.maven.permission.SYNC`. Debug authority `com.maven.diet.dev.sync`.

**Not a Fraud should (next, in its chat):** read the diet status like the workout status; route meal / water
nags into its routine "anger" (one nag system — Twig's own notifications can be switched off in Twig's
settings); tick its meal routine items from `slotsLogged`; show clarity/energy in its "How's today"; feed
Body stats; pass **sleep hours** to Twig later so bad-day remedies and the insights can use sleep.

## 5. Roadmap

1. **v0.1 (built):** diary, foods, meals/recipes, water, weight trend, targets + the math, adaptive maintenance,
   fasting, check-in, remedies, insights v1, nags, Health Connect, workout link, sync. *Start logging now —
   2 weeks of weigh-ins + complete days unlock the real maintenance number.*
2. **Food data:** ~~label scan~~ (0.2.0) · Maven's own foods and recipes · USDA FoodData Central subset bundled
   offline for full micronutrients · barcode scan (ML Kit + Open Food Facts) for name/brand.
3. **Weekly review:** Monday summary — trend vs goal, maintenance estimate, suggested change, adherence.
4. **Meal planner (Eat This Much):** generate a day from his foods/recipes to hit targets within budget;
   recipe breakdown; grocery list.
5. **Experiments:** pick a question (pre-workout chocolate milk 15 min vs meal 3 h before; 16:8 vs 12:12 fasting),
   the app alternates conditions and compares gym performance (from Troglodyte: e1RM vs plan, reps) and
   energy/clarity ratings; reports with uncertainty.
6. **Cross-app bad-day help** through Not a Fraud (sleep, screen time, workouts, food, water together);
   remedies ranked by what actually helped him (follow-up rating 2 h later).
7. Later: blood work entry (vitamin D, iron…), digestion notes per food, carb tolerance experiments.
