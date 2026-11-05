# Going public: other people, iPhone, app stores

Written 2026-11-05 at Maven's request: "what it would take to move into a universal build for Android and Apple, and
publish for other people (a file my buddies download from the repo, or the app stores). Eliminate everything
hard-coded to me, add onboarding and modules so people only use what they want; onboarding picks goals, takes basic
info, and walks them through the app(s)." Nothing here is built yet. Decisions marked **DECIDE**.

---

## 1. The short version

| Step | What it gets you | Size | Cost / waiting |
|---|---|---|---|
| A. Make it anyone's app (no Maven data, modules, onboarding, help) | The real work; everything else depends on it | Large: 3–4 release rounds | none |
| B. Buddies install from GitHub | APK download + Obtainium auto-updates | Small | Repo is **private** now: make a public releases repo (or the repo public) |
| C. Google Play | Normal install / updates for anyone | Medium (policy work) | $25 once; 12 testers × 14 days; accessibility review |
| D. iPhone | iOS version, fewer powers | Large, and blocking works differently | $99/yr; a Mac or macOS CI; Apple approval for Screen Time |

Order: **A → B → C**, with D started only after A (the shared code has to be made iOS-friendly first anyway).
Your own setup stays exactly as it is: it becomes *your* saved setup, not the app's default.

---

## 2. What's hard-coded to Maven today

Fresh installs are seeded from these; existing installs never re-run them, so moving them out doesn't touch your data.

**Not a Fraud**
- `core/seed/Seed.kt` (456 lines) + `WebAppSeed.kt`: your goals, areas, projects, ME classes and times, semester +
  Thanksgiving, trading 8:30, craft / date-night / Sunday-off blocks, CSWE + scout practice, routine times
  (5:30 wake, cardio, creatine, meals, 10 pm bed), skill trees (climbing, athletics…), web-app tasks.
- `Stats.DEFAULT_AREA_STAT` maps your area ids to stats; `Settings.zoneId = "America/Chicago"` (and `Time.zone`
  fallback, `WebImport`); routine nag text "Tomorrow starts at 5:30"; `Ladder.ESSENTIALS` is fine (generic).
- `RoutinePolicy.SPORT_NOTES`, `WorkoutLink.SPORT_WORDS`: generic enough, but should come from the user's sports.
**Not a Troglodyte**: `Seed.kt` (your spreadsheet: RPE types, maxes, 12-week cycle starting Week 2 · Block 1,
Push/Pull/Legs + 3 extra days).
**Not a Twig**: `Seed.kt` profile (6'0", 21, 200 lb, 13.5 %, recomp), slot times, 12–8 window, 100 oz water;
starter foods are fine as generic staples; a couple of texts say "chicken and rice", "what usually works for you".
**All three**: package names `com.maven.*`, app names, signing key (fine to keep — it's yours).

**Plan:** export your current data as `maven.json` templates (one per app) kept in a private place; the seeds become
*starter templates* the onboarding picks from (e.g. "College student", "9–5 job", "Blank"). Text that assumes you
reads settings instead (bedtime, wake time).

---

## 3. One app with modules, or three apps? — **DECIDE**

**Recommendation: one public app with modules** (Focus & blocking · Planner & tasks · Routine · Stats & skills ·
Workouts · Food), each switched on in onboarding or later in Settings.
- One install, one onboarding, one permission story, one store listing, one Apple entitlement request.
- The three apps already share data through content providers; inside one app that's just shared memory (and
  content providers don't exist on iOS).
- Module off = its tabs, nags, notifications, permissions and planner effects disappear; data is kept.
- Your phone can keep the three separate apps, or move to the merged one (one-time import of all three).

Three separate apps also works for B and C, but triples store work and makes iOS much harder.

---

## 4. Onboarding (first launch)

1. **Welcome** — what the app is, 3 screens max, skippable.
2. **Your goals** — pick from a list (school, fitness, focus, sleep, faith, side projects, career, money…) or write
   your own; each becomes a Goal with an Area. Ask "how important" 1–5 (drives the planner).
3. **Modules** — toggles with one line each; suggested ones pre-ticked from the goals.
4. **Basics** — name (optional), wake / bed times, time zone (auto), school or work schedule (classes from a
   calendar, or "9–5 weekdays", or skip).
5. **Per module setup**, only for those turned on:
   - Focus: which apps to block (suggested from 7-day usage), focus apps, ladder on/off, Level 3 apps.
   - Routine: starter habits from templates (morning, meals, workout, bedtime), times from step 4.
   - Workouts: experience level → program preset (full body 3×, upper/lower 4×, PPL 6×), days per week, minimum.
   - Food: height / weight / age / goal (cut / maintain / bulk), meal times, fasting on/off.
6. **Permissions, one at a time, each with its own explanation screen** (this doubles as Google's required
   "prominent disclosure" for accessibility, usage access, notifications, Health Connect, calendar).
7. **Tour** — first visit to each tab shows 2–4 coach marks ("tap here to start", "long-press to…").
   Every page gets a **?** that reopens its help; More → "Show the tour again".

---

## 5. Distribution

### B. Buddies from GitHub (cheapest, first)
- Make a **public repo just for releases** (e.g. `not-a-fraud-releases`) and have the release workflow publish
  APKs there; the code repo can stay private.
- Friends install the APK once and add the repo to **Obtainium** for automatic updates.
- They must allow "install unknown apps", and the accessibility setting on Android 13+ needs "Allow restricted
  settings" for sideloaded apps (onboarding should show how).

### C. Google Play
- $25 developer account. New personal accounts must run a **closed test with 12+ testers opted in for 14 days in a
  row**, then apply for production (review usually ≤ 7 days). [Google Play help 14151465]
- **AccessibilityService** (the guard): allowed for non-accessibility apps, but needs the Play Console declaration,
  an **in-app prominent disclosure with explicit consent**, and a **video** of the disclosure, consent and the
  feature. [Play help 10964491] Reports from 2026 show screen-time apps getting rejected until the disclosure was
  right; one moved to usage-stats polling instead (~0.8 s slower to notice an app). Plan: keep accessibility, build the
  disclosure into onboarding, record the video.
- Also needs: QUERY_ALL_PACKAGES justification (or switch to `<queries>` for launcher intents, which is enough),
  Health Connect permissions declaration, privacy policy URL, data-safety form.
- **Risky for Play, keep for the GitHub build only (flavor `full` vs `play`):** guarding the Settings pages /
  uninstall (Medium lock), and anything that looks like stopping a user removing the app. WRITE_SECURE_SETTINGS is
  harmless (only granted by adb) but needs no Play mention.

### D. iPhone
- **Code:** the shared logic (`core`, `workout-core`, `diet-core`) is plain Kotlin but uses Java APIs in 27 files
  (`java.time`, `java.io.File`, `String.format`, `UUID`). Convert to Kotlin Multiplatform (kotlinx-datetime,
  okio / expect-actual) — mechanical, then the planner, routine, stats, workout and food logic run on iOS unchanged.
  UI: Compose Multiplatform is stable on iOS since 1.8 (May 2025), so most screens can be shared too.
- **What iOS can't do the same way** (Screen Time API = FamilyControls + ManagedSettings + DeviceActivity):
  - Blocking = Apple's "shield" over apps/sites the user picks; the app only gets opaque tokens (it can't see
    which apps are installed or which is open right now).
  - No live "which app is in front", no reading browser address bars (web blocking is by domain list instead),
    no notification mirroring → **no reply windows**, no "typing keeps it open", no unlock-to-app, no settings guard.
  - Credits / unlocks become: lift the shield for N minutes via the shield's button + a DeviceActivity schedule.
  - Usage limits via DeviceActivity thresholds (good enough for "time in blocked apps" points on the ladder).
  - Health: HealthKit instead of Health Connect.
- **Accounts:** $99/yr Apple Developer; **Family Controls (Distribution) entitlement requested per app and per
  extension, manually reviewed** (reports: days to weeks). TestFlight for buddies. Builds need macOS — GitHub's
  macOS runners can build and upload, so no Mac is strictly required, but testing on a real iPhone is.

---

## 6. Phases

**Phase 1 — anyone's app (Android).** Module system + Settings → Modules; onboarding (§ 4); templates instead of
seeds; text from settings; help (?) on each page + tour; merge the three apps if decided (§ 3), with a one-time
import from the separate apps. Your phone: keep using your data; a "Load Maven template" exists only in debug.
**Phase 2 — buddies.** Public releases repo, Obtainium instructions, sideload accessibility walkthrough, simple
privacy page (everything stays on the phone; only Open Food Facts lookups leave it), crash reports off by default.
Get feedback from 3–5 friends before Play.
**Phase 3 — Play.** `play` flavor, disclosure screens + video, store listing, 12 testers × 14 days (the buddies).
**Phase 4 — iOS.** Kotlin Multiplatform conversion of the cores, Compose Multiplatform UI, Screen Time blocking,
HealthKit, App Group storage; entitlement request early (it's the long pole).

---

## 7. Decisions (2026-11-05)

1. **Keep three separate apps** (Maven: the menu layout is the main concern; separate keeps each app's own menu).
   Each gets its own onboarding; the first one installed offers the others, and onboarding answers already given
   (goals, wake / bed) are shared through the sync provider so nobody answers twice. Modules still exist *inside*
   Not a Fraud (blocking, planner, routine, stats, skills) so people can switch off what they don't use.
2. Names stay (Not a Fraud / Not a Troglodyte / Not a Twig); code repo stays **private**, with a public releases repo.
3. **GitHub first, then Play** (buddies become the 12 testers).
4. **iPhone in parallel**: start the Kotlin Multiplatform conversion of the three cores and the Apple
   Family Controls request (only Not a Fraud needs it) alongside Phase 1. On iOS the three apps share data through
   an App Group instead of content providers.

**Size (2026-11-05 release APKs, universal, not shrunk):** Not a Fraud 51 MB, Troglodyte 48 MB, Twig 113 MB
(ML Kit text + barcode models). Turning on R8 shrinking and shipping per-device splits (Play does this with App
Bundles; GitHub can publish arm64-only) should bring them to roughly 10–15, 10–15 and 25–35 MB. One merged app
would be ~30–45 MB, smaller than the three together because Compose / Kotlin are only included once.

Sources: Google Play AccessibilityService policy (support.google.com/googleplay/android-developer/answer/10964491);
Play testing requirements for new personal accounts (…/answer/14151465); dev.to report of a screen-time app's Play
review (2026); Apple Family Controls entitlement guide (newly.app/how-to/family-controls-entitlement) and Apple
developer forums; JetBrains: Compose Multiplatform 1.8.0, iOS stable (May 2025).
