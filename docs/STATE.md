# Not a Fraud (formerly Project Max) — state of the build

Living doc. Read this first in any new chat about the app. Last updated 2026-10-20.
Repo: **github.com/KAWAbungaKid/not-a-fraud** (private). App ID `com.maven.notafraud`.

---

## 0. Where things stand (2026-10-20)

v0.1.0 was built overnight in one Claude session: phone app + Windows tray app, all from one repo,
CI-built and released on GitHub. **Nothing has run on the real S26 Ultra yet.** The next step is
Maven installing it and working through `docs/SETUP.md`, then a week of living with it.

| | Capability | Status |
|---|---|---|
| 1 | App blocking + earn-to-unlock | Built (accessibility guard, block screen, credit ledger, emergency unlock) |
| 2 | Notification-bar "do this now" + escalating nags | Built (event-driven + exact alarm per block boundary) |
| 3 | Day-plan generator | Built in `:core`, tested (anchors, calendar, tasks, rough-day softening, craft-block rule) |
| 4 | To-do / project store | Built (JSON store, seeded from the web app's real tasks) |
| 5 | Phone + PC screen time | Built (UsageStatsManager; JNA tracker on Windows) |
| 6 | Stats, XP, levels, decay | Designed only — next design conversation |
| — | Snapchat mirror | Built (NotificationListenerService + inline reply when Snap offers it) |
| — | Health Connect sleep / workouts / steps | Built; unverified against Samsung Health |
| — | Phone ↔ PC sync | Not built. Needs a Firebase project on Maven's Google account |

**Unverified until the real phone answers:** whether One UI kills the guard (watchdog notification
will say so), what Samsung Health actually writes into Health Connect, whether Snapchat's
notifications carry a reply action, and how the earn rates / nag ladder feel.

### How work happens
Claude writes all code and pushes to `main`; GitHub Actions is the compiler (the Claude sandbox
can't reach Google Maven). CI runs core unit tests, builds the APK, packages the Windows MSI, and
runs an emulator smoke test on every push. Tagging `v*` publishes a GitHub Release with the signed
APK + MSI. Maven installs releases on the phone from the GitHub releases page; data and
permissions survive updates because the signing key is fixed (`keystore/`, committed to the
private repo on purpose — the repo is the backup). See `CLAUDE.md` in the repo for the CI-log
workaround.

---
## 1. What this is

A personal productivity system for Maven: a junior ME student (biomedical minor, graduating Fall 2027, targeting bionic prosthetics / medical device R&D) who takes on more than fits and burns out in cycles.

The product, in his words, turns life into a Fallout-style stat game — but the **behavioral half is the point and the stats are the reward layer**. The app must:

1. **Block or gate distracting apps**, with earn-to-unlock conditions rather than a hard lock (e.g. 10 min in a reading app buys 10 min of Instagram).
2. **Keep a live "what you should be doing right now" in the Android notification bar**, updated as the day moves.
3. **Generate the day's schedule** from his to-do list, calendar, goals and fixed anchors — and adapt when a day goes sideways (bad sleep, something spontaneous).
4. **Nag with escalating notifications** when he's stuck in a time-sink app, with the way out being an action (walk, workout, start a task), not a dismissal.
5. Later: stats, XP, levels, decay — the gamification layer.

Snapchat is a special case: he must stay reachable there, so it can't be hard-blocked. Solved with a `NotificationListenerService` that mirrors Snap messages into the app's Inbox, which makes it safe to block Snapchat itself.

Blocking and notifications come first because they're what actually changes behavior; the stat engine comes last.

---

## 2. What exists

**Not a Fraud v0.1.0** (repo above). Three Gradle modules:

- `core/` — pure Kotlin, zero Android imports. Models, scoring engine (verbatim port), day planner, now-resolver, credit ledger, guard policy, nag policy, desktop distraction policy, ICS parser, JSON store, web-app importer, seed data. ~30 unit tests.
- `android/` — Kotlin + Compose. `GuardService` (accessibility), `BlockActivity`, `Notifier` + `ActionReceiver` + `Alarms`, `MirrorService`, `HourlyWorker`, sensors (UsageStats, Health Connect, phone calendar via `CalendarContract`). Tabs: Now / Plan / Tasks / Inbox / Setup.
- `desktop/` — Compose Desktop tray app for Windows. JNA foreground window + idle; usage per program; distraction nudges by process name or window title (catches YouTube in Chrome); optional minimize; start-with-Windows via the Run registry key. Ships as an MSI.

**Web app** (`https://claude.ai/artifact/Wgupx2fdTNfnNwV82Tgcei`) — the prototype. Its projects and tasks were read on 2026-10-20 and baked into the Android seed, so the phone starts with the same list. The Setup tab can also import a JSON export of it.

**Data model** (as built; one JSON file per device, atomic writes, daily .bak):

```
AppData   projects, tasks, events, sessions, ideas, anchors, rules, ledger, settings,
          active, days (sleep/steps/rough), usage (per device per day), messages, todayPlan
Task      title, projectId, estMin, dueAt, priority, energy, minBlockMin, status, loggedMin,
          createdAt, lastWorkedAt, doneAt, notBefore (craft-block rule / snooze), icsUid, notes
Anchor    title, kind, startMin, endMin, days | dates, from/until, skipDates, protected, yieldsToDated
Ledger    balanceMin, entries, unlockedUntil{pkg}, emergencyUses
```

**Scoring engine** (validated; ported verbatim, unit tested):

```
est    = max(10, estMin);  left = max(10, est - loggedMin)
slack  = hoursUntilDue - left/60
urgency = slack > 0 ? 100/(1 + slack/12) : 100/(1 + (-slack)/36)   (no due date → 8)
prio   = high 1.45 | normal 1.0 | low 0.65
fit    = freeWindow < minBlockMin ? 0.2 : freeWindow >= left ? 1.15 : 1.0
energy = exact match 1.15 | task high & user low 0.45 | task low & user high 0.85 | else 1.0
neglect = min(18, daysSinceLastWorked * 3)
score  = (urgency * prio + neglect) * fit * energy
```

**Planner rules as built:** anchors and calendar events first; free windows (≥15 min) filled greedily by score, chunks ≤90 min with a 10-min break after ≥45; craft blocks are containers filled only with project-kind tasks (side projects, PT device, ideas); protected anchors (date night, Sunday after 5) get nothing. **Rough day** = under 6 h sleep from Health Connect, or tapped by hand → energy LOW, 50-min chunks, 4 h focus cap, gentler 3-step nag ladder. Plan is generated once per day and re-planned from "now" when tasks change or on Replan; past blocks are kept.

**Credits as built:** 1 focus-app minute or 1 logged task minute = 1 credit minute, max 120 banked, start at 15. Spend 5/10/15/30 per app. Emergency unlock: 60 s wait + typed phrase, 5 min, 3 per day, logged.

**Nags as built:** while an unlocked blocked app is in front, at 3/8/15/25/40 min (rough day: 10/25/45). Every nag offers an action ("Start it now", "Walk 5 min"). None during protected time. Copy lives in `core/.../guard/NagPolicy.kt`.

---
## 3. The spreadsheet: what he wants tracked

Source: `PROJECT_MAX_STATS_2.xlsx`. Six stat categories, each a tree of sub-stats scored roughly 1–10, fed by weekly day-by-day habit grids, plus a time-budget sheet and two early Gantt trees.

| Category | Sub-stats | How he scores them |
|---|---|---|
| **BODY** | Health, Strength, Agility, Sleep | Diet (calories met 1/0), vitamins 1/0, water (gallons), workout frequency 1/0, lift goals (bench 255→315, squat 275→315, deadlift 335→495, as progress ratios), leg work, cardio 1/0, stretching 1/0, sleep hours + start/end times |
| **MIND** | Intelligence, Mentality, Creativity, Clarity | Grades on 4.0 scale per course, independent learning hours/week, fulfillment, stress, discipline, art, spiritual (Bible / pray / church streaks), organization (gmail, room, photos), joy 1–10 |
| **SOCIAL** | Charisma, Relationship, Confidence, Boundaries | Named people + a rating of how much he reaches out to old friends and family |
| **HYGIENE** | Face, Body, Teeth, Environment | Streaks: face washing, shower + deodorant, brushing; room + car cleanliness rating |
| **WORK** | Knowledge, Practice, Networking | Research streaks + "how close to where I want to be"; outreach streak + whether a job is lined up |
| **MONEY** | Spending, Projection, Changes | Spending over baseline necessity in 10% increments; how many years of runway; count of unexpected large expenses |

Also in the file: a **TIME** sheet budgeting a 168-hour week into sleep / work / school / workout / social / improvement / fraternity / food with an "unaccounted" remainder, and **Neural interface** / **MIND MAP** sheets — early Gantt-style trees with "PRIME MAVEN LIVING OUT FULL POTENTIAL" at the root branching into R&D for prosthetics, then internship / school / research. The Gantt requirement comes from these.

His own note in the MIND sheet: *"INTEGRATE INTO SOMETHING THAT INPUTS INTO EXCEL."* He already knew manual entry was the failure point.

### Why the spreadsheet died — the central design constraint

Nearly every cell is a placeholder `10` or `#N/A`. Roughly 100 metrics, almost all requiring daily manual entry. The logging burden exceeded what he would sustain, so the data went fake.

**Rule for the app: if a metric isn't sensor-filled or a single tap, it doesn't exist.** Target under 60 seconds of manual input per day. Roughly 20 live metrics, at least 12 of them automatic.

### Draft classification (NOT yet decided — this is the next design conversation)

- **Automatic, Health Connect:** sleep duration, sleep start/end, workouts, cardio, steps. *(Already read into `days` by v0.1.)*
- **Automatic, phone/PC:** screen time per app, time in focus apps vs time-sink apps. *(Already captured.)*
- **Automatic, derived in-app:** hours per project (session timer), plan adherence → "Discipline". Derive, don't ask.
- **One tap daily:** diet target met, vitamins, water, teeth, face, shower, Bible/pray, stretching. Streaks — one bit per day each.
- **Weekly, one rating:** happiness, fulfillment, stress, room/car cleanliness, reaching out to people.
- **Periodic manual:** lift numbers vs goals, grades, spending vs baseline.
- **Cut or merge:** duplicated Organization and Art rows, the Agility sub-splits, Boundaries, and the Clarity/Mentality overlap.

---

## 4. Technical decisions

- **Android, native Kotlin + Jetpack Compose.** App blocking requires an `AccessibilityService`, which rules out web, Flutter and React Native.
- **Sideloaded, not Play Store.** Installed from GitHub Releases on the phone. Sideloaded apps need *App info → ⋮ → Allow restricted settings* before accessibility / notification access can be enabled.
- **Signing key committed to the private repo** (`keystore/notafraud.jks`) instead of a separate backup. The repo is the backup; debug and release are signed with the same key. Never replace it.
- **Debug build is a separate app** (`com.maven.notafraud.dev`, label "NaF dev") so development never touches real data.
- **`:core` pure Kotlin, zero Android imports.** Held from the first commit; the Windows app uses the same planner, scorer and nag policy.
- **Windows is Kotlin (Compose Desktop + JNA), not Python** — changed 2026-10-19 because Maven wants everything native and Claude writes the code, so shared `:core` logic beats a second implementation.
- **Local JSON store instead of Room**, one file per device. Data is small; atomic writes; no KSP/annotation-processing version risk.
- **Firebase Firestore for sync — still the plan, not built.** Needs a Firebase project on Maven's Google account. Firestore has no official desktop SDK, so the PC side will use the REST API.
- **Phone calendar via `CalendarContract`** instead of .ics fetching: Google Calendar syncs into the phone, so no CORS problem and no connector. .ics file import is still in Setup.
- **CI is the compiler.** GitHub Actions; logs are pushed to `ci-logs/<job>` branches because the Claude sandbox can't download Actions logs.
- **Toolchain (2026-10-20):** Gradle 9.8.2, AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, compileSdk 37, targetSdk 36, minSdk 34, Compose BOM 2026.09.00, Compose Multiplatform 1.12.2, Health Connect 1.1.0, JNA 5.19.1.

---

## 5. Battery strategy (as built)

- **The `AccessibilityService` is the persistent host.** No foreground service, no wakelocks.
- **Only `TYPE_WINDOW_STATE_CHANGED`** — one event per app switch. `packageNames` is *not* filtered, deliberately: the guard has to see the switch *away* from a blocked app to stop nags and bank focus credits. Overlay windows (keyboard, system UI) are ignored so they don't count as a switch.
- **Notification updates on events only** (app switch, block boundary, task change), and only when the text changed.
- **One exact alarm at a time**: the next block boundary (~10–15/day), falling back to inexact if exact alarms aren't granted.
- **Handlers only while a watched app is open** (nag chain, unlock expiry); cancelled on every switch and on screen off.
- **Hourly WorkManager job** (battery-not-low): watchdog, usage snapshot, calendar + Health Connect sync. Also runs on app open.
- **Samsung setup by hand** (in `docs/SETUP.md` and the Setup tab): battery unrestricted, out of Sleeping / Deep sleeping apps, into Never sleeping apps, "Put unused apps to sleep" off. The hourly watchdog posts "Guard was stopped" if One UI kills it.

---

## 6. Android API map

| Need | API | Permission / setup |
|---|---|---|
| Per-app screen time | `UsageStatsManager.queryEvents` | Usage access (Settings) |
| Live foreground app | `AccessibilityService` | Accessibility settings + restricted-settings allow |
| Blocking | `BlockActivity` launched by the service (accessibility services are exempt from background-launch limits) | — |
| Notification bar | Ongoing low-importance channel; re-posted if swiped away | `POST_NOTIFICATIONS` |
| Snap messages | `NotificationListenerService`, `RemoteInput` reply | Notification access |
| Sleep, workouts, steps | Health Connect 1.1.0 | Per-type read grants; Samsung Health must write into HC |
| Calendar | `CalendarContract.Instances` | `READ_CALENDAR` |
| Block transitions | `AlarmManager.setExactAndAllowWhileIdle` | `SCHEDULE_EXACT_ALARM` |
| Background | `WorkManager` hourly | — |
| App picker labels | `PackageManager` | `QUERY_ALL_PACKAGES` (fine when sideloaded) |

---

## 7. His schedule anchors (seeded; editable in Setup → Weekly anchors)

- **Daily 6:00–7:00** cardio + shower.
- **Classes** (Aug 24 – Dec 11, skipped Thanksgiving week Nov 23–27 — *assumed*, confirm): ME 3411 T/Th 8:00–9:15 · ME 3521 T/Th 9:30–10:45 · ME 3525 T/Th 11:00–12:15 · BIO SCI 3333 M/W/F 11:00–11:50 · ME 4840 lab M/W 2:00–2:50 and T 3:00–4:50.
- **Market open 8:30–9:30 Mon/Wed/Fri only.** Tue/Thu protected for ME 3411 (third attempt).
- **Lift** Mon/Tue/Wed 5:00–6:00. **Lunch** 12:15–12:45 daily and **dinner** 6:15–6:45 Mon–Thu, Sat were *added by Claude* so the planner doesn't fill mealtimes — toggle off if wrong.
- **Friday** craft block 4:00–6:00 (filled with project tasks and ideas), **date night 6:00 on, protected**.
- **Saturday** craft 8:00–12:00 on non-session days. **Session days** (biosensor): load 1h/4h/8h groups 8:00, test at 9:00, 12:00, 16:00. Dates: Oct 24, Oct 31, Nov 7, Nov 21, Nov 22, Dec 5 (Oct 17/18 past). *Oct 18 and Nov 22 are Sundays* — seeded as given; confirm whether those are two-day sessions.
- **Sunday** reset: weekly review 9:00, CSWE practice 10:00–11:30, scout manual section 1:00–1:45, **off from 5:00, protected**.

**Remaining Fall 2026 exams:** A&P Oct 21, Nov 13, Dec 11 · ME 3411 Nov 17 and Dec 16 (12:30–2:30) · Heat Transfer Nov 17 and Dec 15. Thermo and Instrumentation dates still unknown (task "Email thermo and instrumentation professors" is in the list). Nov 17 carries two exams, four days after an A&P test. *Note: the web app has "Study for Heat Transfer Final" marked done (Oct 14) — likely a mis-tap; check.*

**Craft-block rule (built):** ideas captured midweek can be promoted to a task, but the planner won't place it before Friday 4pm.

---

## 8. Build order

0. ~~Hello-world~~ → v0.1.0 built: everything in section 0's table except sync and stats.
1. **Install + 8/8 Setup + Samsung battery steps** (Maven, ~20 min). Then a week of normal use.
2. **Report back:** did the guard survive overnight, does sleep show on the Now tab, did a Snap reply work, what felt wrong. Screenshots are enough.
3. **Tuning release(s)** from that feedback.
4. **Firestore sync** (Maven creates the Firebase project, ~5 min), then PC usage and credits shared with the phone.
5. **Stats, XP, decay** after the metric-cut conversation (section 3). Winter break.

**Pace / risk:** Maven's time goes to using the app, not building it. The failure mode is still pulling hours from the PT device, his top-priority project.

---

## 9. Open questions

- Which ~20 metrics survive the cut (section 3).
- XP and decay curves.
- Whether the Gantt / project-tree view feeds the scheduler or is just a view.
- Nag tone in practice — v0.1 copy is in `NagPolicy.kt`; tune after a week.
- Earn rate (1:1), 120-min cap, 15-min starting balance, 3 emergency unlocks/day — all guesses to tune.
- PC and phone keep separate credit ledgers until sync exists.
- Thanksgiving-week class skip, the two Sunday session dates, and the added meal anchors (section 7) need confirming.
