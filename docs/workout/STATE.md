# Workout app — state of the build

Living doc for Maven's workout tracker (the Hevy replacement that talks to Not a Fraud). Read this first in
any chat about the workout app. Not a Fraud's doc is `docs/STATE.md`; § 16 there is the integration summary.
Last updated 2026-10-26 (v0.1.0, first build).

- **Lives in:** the Not a Fraud repo (`github.com/KAWAbungaKid/not-a-fraud`), modules `workout-core/` (pure Kotlin)
  and `workout/` (Android). Same repo so it shares the toolchain, CI and — on purpose — the signing key.
- **App ID** `com.maven.workout` (label "Workout"); debug `com.maven.workout.dev` ("Workout dev").
- **Version** `workoutVersionName` / `workoutVersionCode` in `gradle.properties` (separate from Not a Fraud's).
- **CI:** `.github/workflows/workout-ci.yml` (core tests, debug APK, emulator smoke test). Logs on branches
  `ci-logs/workout-core`, `ci-logs/workout-android`, `ci-logs/workout-emulator`. Not a Fraud's `ci.yml` ignores
  workout-only pushes.
- **Release:** bump the version, add `docs/workout/releases/vX.Y.Z.md`, push. `workout-release.yml` builds the
  signed APK and publishes GitHub Release `workout-vX.Y.Z` (not marked Latest, so Not a Fraud stays the top
  release). The APK is mirrored to branch `ci-logs/workout-dist`. Install from the releases page like Not a Fraud.

---

## 1. Where it came from

`workout.xlsx` (Google Sheets export, three sheets). Analysed 2026-10-26:

- **REFERENCE** — RTS-style RPE chart (reps 1–12 × RPE 6–10 → % of 1RM); exercise → type map; per-type
  progression tables (`RPE<type>` / `REPS<type>` rows × 12 columns = 4 weeks × 3 blocks).
- **Maxes** — "245x4" per exercise → e1RM = weight ÷ chart%(reps, RPE 10). Overwritten by hand, no history.
- **Phone** — pick Week / Day / Block; shows today's PPL×2 workout: main lift top set + back-off sets, then
  accessories, weight = chart% × e1RM, MROUND 5.

Sheet bugs not carried over: volume ignored set count; "MAX" day had no weight; Arnold press missing from the
lookups; reps > 12 broke the chart; Week/Block had to be moved by hand.

**Key finding:** every value in the RPE chart depends only on reps + (10 − RPE). The app stores one 16-value row
and interpolates (so RPE 8.5 works) and extrapolates past 12 reps.

## 2. What Maven asked for (2026-10-26)

1. A real tracker like Hevy: log every set, exercises added over time (sheet exercises imported), each exercise
   picks a **type** so it's programmed like the sheet. Programs are **presets** people can copy/change — only
   preset now is his sheet. **Updated e1RM every workout.** **Injured** and **short** modes for hurt days / off days.
2. Consistency first: **minimum 3 workouts/week**. 6-day week = Push / Pull / Legs (days 1–3, the minimum split),
   then Chest & Back / Shoulders & Arms / Legs (days 4–6) — smaller muscles and shoulder health get their own
   day, and it's easy to schedule around climbing ("climbing tomorrow → chest & back OK, not shoulders & arms").
   Lots of unconventional and **timed** exercises: hit Go on a plank, stop at failure, that's the record.
   **Auto-advance**, **suggest today's workout**, **break bad habits** (only ever doing push), but always let
   him choose a different day.
3. Arnold press dropped; shoulder press is DB now and should use the incline type. Oct 26: 75 × 8, could have
   done 9–10 (entered as RPE 8.5).
4. The **workout app owns the split and days/week**. It tells Not a Fraud the minimum and the full week; Not a
   Fraud plans the full week (6) and **freaks out when the minimum is at risk** (e.g. Thursday with nothing
   done → Thu, Fri, Sat/Sun all need one).

## 3. How it works (v0.1.0)

### Data (`workout-core/.../Model.kt`, one JSON file `filesDir/workout.json`, atomic writes, daily .bak)

```
WorkoutData  exercises, progressions (types), programs (presets), activeProgramId, cycle{week, block,
             countedThrough, cyclesDone}, sessions, maxes (typed-in), injuries, active (workout in progress), settings
Exercise     name, measure (WEIGHT_REPS | REPS | TIME), progressionId (type), muscles, joints, roundTo, archived
Progression  name, steps[weeksPerBlock × blocks] — Step(sets, reps|null=to failure, seconds, rpe, topReps, test)
Program      name, weeksPerBlock, blocks, days[DayTemplate(name, exerciseIds)], coreDays (the minimum split)
Session      template, week, block, mode (NORMAL | SHORT | INJURED), date, start/end, entries[Entry(exercise, sets[SetLog])]
SetLog       kind (WARMUP | TOP | WORK | TEST), weight, reps, seconds, rpe, done + target* (what the program asked)
```

Type editor text format per week: `3x4 @7 top3`, `3x10 @9`, `3x1 @10 top1 test`, `3xF` (to failure), `3x45s`.

### Weights (`Prescriber`, `Strength`, `Rpe`)

- Step for this exercise = its type's step at index `(block−1)·weeksPerBlock + (week−1)` (last step repeats if the
  type is shorter than the cycle).
- Weight = chart%(reps, RPE) × **training max**, rounded to the exercise's increment (5; cables 2.5).
- **Training max** = best e1RM in the last 42 days (setting) across typed maxes and every finished workout's best
  set; falls back to the latest point after a long layoff. Injured-mode workouts are shown but never count.
- Ticking a set fills any blank field (weight, reps, RPE) from the target. A set with no RPE and no target counts as RPE 10 (the conservative reading).
- Top set (main lifts) before the back-off sets; test week = "work up to a max single", suggested at the e1RM.
- Exercises with no type: planned as a repeat of last time.
- Timed / bodyweight: "to failure" sets with **your best as the number to beat**; PRs tracked.

### The week (`Week`, `Risk`)

`done` = distinct days with a finished workout this week (Mon–Sun, setting). `daysLeft` includes today unless you
already trained today. `needed = min − done`, `slack = daysLeft − needed`.

| Risk | When | Meaning |
|---|---|---|
| MET | needed = 0 | minimum done |
| ON_TRACK | slack ≥ 2 | |
| TIGHT | slack = 1 | one spare day (Thursday, 0 done, min 3) |
| MUST_TODAY | slack = 0 | every remaining day must be a workout |
| MISSED | slack < 0 | can't make it this week |

The app's own reminder: one notification a day at 4 pm (setting) when TIGHT or MUST_TODAY and not trained today.
Not a Fraud is meant to do the real escalation (§ 5).

### Cycle (`Cycle.roll`, run on app open and on every sync read)

When a calendar week ends: **EARNED** (default) — the program week advances only if that week met the minimum,
otherwise it repeats (so 1 workout in a week doesn't jump the RPE ramp). Also CALENDAR / MANUAL. After block 3
week 4 it wraps to a new cycle. **Re-entry:** no workouts for 14 days (setting) → current block restarts at week 1.
Week / Block can be set by hand on the Program tab. Seeded at Week 2 · Block 1 (where the sheet was).

### Today's suggestion (`Suggest`)

Core days (first `coreDays` = Push/Pull/Legs) not yet done this week come first, then extra days (4–6) in order,
then anything. **Habit-breaker:** among core days, the one done least in the last 4 weeks jumps the queue
("Legs 0× vs Push 4× in 4 weeks — evening it out"). Choosing a day that's already ahead shows a heads-up.
"Empty workout" is always available.

### Modes

- **Short:** keeps exercises in order until `shortMinutes` (35) is used, ≤ 2 working sets each, no top set; the
  first exercise (main lift) is always kept.
- **Injured:** add an injury (joints: neck, shoulder, elbow, wrist, lower back, hip, knee, ankle). Exercises that
  load an injured joint are skipped; each can be swapped for 2 light sets at RPE 6. Session counts toward the week,
  not toward maxes. Mark the injury healed when done.
- Mode can be switched mid-workout; completed sets are kept.

### Screens

Today (week strip + risk, suggestion with mode chips and a preview of weights, other days with 4-week counts,
injuries) · Workout (Hevy-style set rows: weight / reps / RPE with targets as placeholders, tick fills targets,
rest timer with vibration, Go/Stop stopwatch for timed sets, add/move/skip/remove exercises, notes, finish →
summary with PRs) · History (by week, n/min per week) · Exercises (library, editor: measure, type, muscles,
joints, rounding, typed max "245x4@9", e1RM chart) · Program (active program, week/block, presets: use /
duplicate / edit / new; day editor; type editor grid) · Settings (minimum, full week, week start, advance rule,
re-entry, short minutes, rest, max window, units, reminder, sync preview, JSON backup copy/import).

## 4. Seed (`Seed.kt`) — from the sheet, plus Oct 26 changes

- Types: Bench / Deadlift / Squat (main: top 3/2/1 by block, back-off 3×4→3→2, 3×5→4→3, 3×8→5→3; RPE 6→9,
  6→9, 7→10; week 12 = test), Secondary press (3×6→5→4, same RPE ramp), Type 1 (3×8,8,9,10 @9; wk1 @6),
  Type 2 (3×10,10,11,12 @9), Type 3 (3×8 RPE ramp), Chin up (3×1), Hold to failure (timed).
- 29 exercises incl. lat pulldown and cable curl (were in Maxes but not programmed → Type 2) and Plank (new, timed).
- Maxes: every Maxes!C entry at RPE 10, dated the install day; DB shoulder press 75×8 @8.5.
- Program "Maven PPL + upper/arms (from sheet)": Push, Pull, Legs (sheet's Mon/Tue/Wed), Chest & Back,
  Shoulders & Arms, Legs 2 (sheet's Saturday legs).

**Claude's guesses — confirm with Maven:** contents of Chest & Back and Shoulders & Arms (built from the sheet's
exercises); deadlift now once a week (Pull only); plank on both leg days. Placeholder-looking maxes kept as
given: chin up 5×1, leg curl 20×1, tibia 20×1, hip raise 20×2, hack squat 30×12 — they fix themselves once real
sets are logged (training max = best recent).

## 5. Sync contract with Not a Fraud

Both apps are signed with `keystore/notafraud.jks`. The workout app declares the signature permission
`com.maven.permission.SYNC` (Not a Fraud must declare + use the same one, so install order doesn't matter).

**Read:** `ContentResolver.query(content://com.maven.workout.sync/status)` → one row, column `json`:

```json
{ "schema":1, "generatedAt":…, "today":"2026-10-29", "weekStart":"2026-10-26",
  "minPerWeek":3, "targetPerWeek":6, "doneThisWeek":0, "doneDates":[], "trainedToday":false,
  "daysLeft":4, "needed":3, "slack":1, "risk":"TIGHT", "message":"0 of 3 · 3 more in 4 days — one spare day left.",
  "suggestedToday":"Push", "suggestedReason":"…", "inProgress":false, "inProgressName":null,
  "inProgressStartedAt":null, "lastWorkoutDate":"2026-10-24", "program":"…", "week":2, "block":1,
  "streakWeeks":0, "injured":["SHOULDER"] }
```

`content://com.maven.workout.sync/sessions?since=2026-10-01` → `json` = list of
`{id, date, name, mode, startedAt, endedAt, sets, minutes}`. Debug build authority: `com.maven.workout.dev.sync`.
Fields are a contract: add, don't rename. Reading `/status` also rolls the cycle forward.

**Push:** when anything Not a Fraud cares about changes (done count, risk, in-progress, suggestion, min/target),
the workout app sends broadcast `com.maven.workout.CHANGED` explicitly to `com.maven.notafraud` and
`com.maven.notafraud.dev`, requiring `com.maven.permission.SYNC`. Not a Fraud registers a manifest receiver for it
and re-reads `/status`.

## 6. Backlog / ideas (not built)

- Import Hevy history (Hevy exports CSV) so e1RM history starts full.
- Warm-up set generator and plate calculator for the top set.
- Rest timer as a notification when the screen is off (now in-app only, screen kept on during a workout).
- Write finished workouts to Health Connect (ExerciseSession) so Samsung Health / Not a Fraud's stats see them.
- Other activities (climbing, sports) — log here or keep in Not a Fraud's sport rule? Should they count toward
  the minimum? Undecided.
- "Climbing tomorrow" planning: Not a Fraud knows the calendar; it could ask the workout app for a day that avoids
  shoulders/arms (muscles are already on every exercise).
- Edit a finished workout (now: delete only).
- Supersets; per-muscle weekly volume; diet app (separate, later).
- Onboarding with neutral presets for other people (only Maven's preset exists).
