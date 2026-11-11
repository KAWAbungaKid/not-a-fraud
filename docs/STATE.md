# Not a Fraud (formerly Project Max) — state of the build

Living doc. Read this first in any new chat about the app. Last updated 2026-11-04 (v0.10–0.11: brick ladder, settings lock, practice merge; Twig 0.3, Troglodyte 0.2 — § 22).
Repo: **github.com/KAWAbungaKid/not-a-fraud** (private). App ID `com.maven.notafraud`.

---

## 0. Where things stand (2026-10-26, v0.8.0)

v0.8.0: calendar sync switch-on (real Canvas cause), split Health permission request, numbers settings page, workout app owns days/week (section 18). v0.7.0: homework read fix, one priority per task, Tasks tabs Map/Projects/To-do with tap/hold/+ map, workout app link (section 17). v0.6.0: to-do list, homework calendars with work-ahead, settings lock, purpose mode, typed extra emergencies, block suggestions (section 15). v0.5.0: pages + choosable bottom bar, Classes/semester, constellation skill trees, Health fixes + diagnostics, less hard-coding (section 14). v0.4.0: stats, skill trees, auto re-plan, burnout prompt, weekly check-in, guard log, stricter call check, accent colors (section 13). Earlier:

v0.2 confirmed on the phone (site blocking, Google Calendar writing). **v0.3.0** built from section 11:

| | Capability | Status |
|---|---|---|
| 1 | App + website blocking, earn-to-unlock | Built. v0.3: calls never blocked (AudioManager mode), reply windows on notification tap, typing extends windows (keyboard window watched only during free windows), "still texting", credits wait 60 s, instant emergency, Quick Snap tile, per-app BlockProfile |
| 2 | "Do this now" + nags | Built |
| 3 | Planner | Built. v0.3: 7-day drafts, task kinds (focus / steps+waits / practice / errand / waiting / low-brain), importance 1–5 (area + task) + bump, How's today modes (push / recharge / reset / write off) + advisor, do later / not today / pick a day / something came up, skip + make up any block |
| 4 | Hierarchy map | Built (v0.2) |
| 5 | Routine | Built. v0.3: on-time grading, wake from watch or first unlock, naps split from night sleep, bedtime sound on the alarm stream (gets through DND), sport counts after 3 standard workouts |
| 6 | Google Calendar | Built. v0.3: per-calendar Events / Deadlines / Ignore (Canvas → deadline tasks synced by event id), auto exam study tasks, 7 days written (future = "(draft)") |
| 7 | Catch-up study | Practice item per class, 3 h/week; research area importance 5, research tasks pinned hot; old web-app tasks in a review queue |
| 8 | Stats, skill tree, learning session lengths, burnout signals, hourly re-plan | Built in v0.4 (section 13) |

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
- `android/` — Kotlin + Compose. `GuardService` (accessibility), `BlockActivity`, `Notifier` + `ActionReceiver` + `Alarms`, `MirrorService`, `HourlyWorker`, sensors (UsageStats, Health Connect, phone calendar via `CalendarContract`). Tabs (v0.2): Now / Plan / Routine / Tasks (Map + List) / More (Setup, Inbox).
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
- **Classes** (Aug 24 – Dec 11, skipped Thanksgiving week Nov 23–27 — confirmed 2026-10-20): ME 3411 T/Th 8:00–9:15 · ME 3521 T/Th 9:30–10:45 · ME 3525 T/Th 11:00–12:15 · BIO SCI 3333 M/W/F 11:00–11:50 · ME 4840 lab M/W 2:00–2:50 and T 3:00–4:50.
- **Market open 8:30–9:30 Mon/Wed/Fri only.** Tue/Thu protected for ME 3411 (third attempt).
- **Lift** Mon/Tue/Wed 5:00–6:00. **Lunch** 12:15–12:45 daily and **dinner** 6:15–6:45 Mon–Thu, Sat were *added by Claude* so the planner doesn't fill mealtimes — toggle off if wrong.
- **Friday** craft block 4:00–6:00 (filled with project tasks and ideas), **date night 6:00 on, protected**.
- **Saturday** craft 8:00–12:00 on non-session days. **Session days** (biosensor): load 1h/4h/8h groups 8:00, test at 9:00, 12:00, 16:00. Dates: Oct 24, Oct 31, Nov 7, Nov 21, Nov 22, Dec 5 (Oct 17/18 past). *Oct 18 and Nov 22 are Sundays* — seeded as given; confirm whether those are two-day sessions.
- **Sunday** reset: weekly review 9:00, CSWE practice 10:00–11:30, scout manual section 1:00–1:45, **off from 5:00, protected**.

**Remaining Fall 2026 exams:** A&P Oct 21, Nov 13, Dec 11 · ME 3411 Nov 17 and Dec 16 (12:30–2:30) · Heat Transfer Nov 17 and Dec 15. Thermo and Instrumentation dates still unknown (task "Email thermo and instrumentation professors" is in the list). Nov 17 carries two exams, four days after an A&P test. *"Study for Heat Transfer Final" was ticked in the web app on Oct 14 by mistake (confirmed) and came across as done; untick it in Tasks → Done.*

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

---

## 10. First-install feedback and queued conversations (2026-10-20)

**Works:** install, permissions, blocking, notification, plan — "everything seems to work really well."

**Samsung "never sleeping" step:** Not a Fraud does not appear in Settings → Battery → Background usage limits → Never sleeping apps (it appears in every other list). Likely because battery is already set to Unrestricted, which One UI treats as exempt. Unconfirmed — the real test is whether the hourly watchdog ever posts "Guard was stopped". Setup row 9 text should be softened once confirmed.

**Fresh start on tasks and priorities.** The seeded tasks come from the web app, built weeks ago at the start of the Claude Project, and are stale. Before tuning anything else: go through every imported task and project together — priority, estimate, due date, whether it still matters — and rebuild the list as current. The two Sunday research dates (Oct 18, Nov 22) are part of that review, not separate fixes.

**Hierarchy / tiering — needs a design conversation.** What the app tracks and how it nests: long-term goals (the "Prime Maven" tree), areas, projects, tasks, fixed time (anchors, calendar), daily routine, stats. How tiers relate to scoring and the planner is undecided.

**Daily routine as its own track, probably its own tab.** Sleep, meals, hygiene and similar daily habits are a different kind of thing from tasks. Maven has a real problem with not eating, and similar daily habits slip. Wants:
- Meal times adjustable (today anchors can only be toggled on/off, not retimed).
- A checkbox per meal / habit ("ate lunch"), i.e. the one-tap streaks from section 3.
- Likely a separate Routine tab covering sleep, eating, hygiene. Keep meals and routine separate from the task scorer.

**Web blocking (Brick-style).** Maven doesn't have the Instagram app; he uses it in a browser, so v0.1 blocks nothing for him there. Needs site blocking: Instagram etc. in Chrome / Samsung Internet. Options: (a) the accessibility guard reads the browser's address bar and blocks matching domains — fits the existing service, but must widen events to content changes inside browsers only, and the guard's description ("only reads app names") must be rewritten honestly; (b) a local VPN DNS filter — covers every browser and app, but takes the VPN slot and is more work. Leaning (a), plus optionally blocking browsers that aren't covered.

**Contrast.** Some dim text colours don't contrast well. Quick fix: raise the dim/secondary text colour. Later: theme / colour customization.

**Google Calendar, two-way-ish.** v0.1 already reads the phone's calendar (Google Calendar syncs into it): events become busy blocks, re-read on app open and hourly as a full replace, so no duplicates and deleted events disappear. Not yet decided:
- **Adding tasks from Google Calendar** — e.g. a dedicated calendar whose events become tasks (stable mapping by event id so edits update and deletions remove), vs. Google Tasks (needs OAuth / API, not in the phone's calendar provider).
- **Ignoring routine events** Maven put in Google Calendar as a visual of his day (wake up, etc.). The routine should live in the app as anchors and the app should ignore those calendar entries — by choosing which calendars to read, or a keyword/tag rule. Depends on how his calendars are organised.
- Writing back to Google Calendar (plan blocks visible there) — not requested yet.

**Editing gaps found while explaining the app:** no renaming projects or choosing a project's kind (new projects are always "project"); anchors can be toggled but not retimed, added or deleted; no way to edit research session dates.

### Decisions from the 2026-10-20 design conversation

- **Current priorities:** exams (they live in Google Calendar) and biosensor research ("very hot"). Not currently working. Day-trades every morning at market open, 15–60 min.
- **Hierarchy accepted** (goals → areas → projects → tasks, plus fixed time, routine, stats) and becomes a **visual map** — mind-map style: open a goal, see the areas that affect it, the projects under them, the tasks under each project. Stats are later.
- **Routine gets its own tab** (sleep, meals, hygiene) with **escalating "anger"** when procrastinated: e.g. lunch not eaten by 4pm is bad; going to bed without brushing teeth is bad.
- **Google Calendar model:**
  - Anything Maven puts in Google Calendar is always a fixed **event** to work around (he'll give it accurate times). Calendar entries never become tasks.
  - The app **writes its plan into Google Calendar** (its own calendar) so the day is visible there. No separate "tasks" input calendar.
  - Routine moves into the app; Maven will delete his Google routine calendars, and the app writes routine into Google Calendar the same way.

### More decisions (2026-10-20, later)

- **Goals (his words):** giving back to community and church · being a mentally and physically strong person · developing prosthetics and physical therapy devices · being a man of God · being a good friend/boyfriend/relative.
- **Task list view stays** alongside the map (findable when the map gets big).
- **Routine (his list):** wake 5:30–6:00 · bed by 10:00 · immediately drink fluids · cardio · shower · vitamins + creatine · eating · working out · brushing teeth.
- **Trading every weekday at 8:30** (not just M/W/F). Conflicts with ME 3411 Tue/Thu 8:00–9:15 — open question how to plan it.
- **Anchors must be calibrated, not deleted.** He wants set times so his body gets into the mental space at those times on those days, but the planner must also weigh what's hot. The v0.1 anchors (Friday craft block, Saturday craft, Sunday review, CSWE, scout manual) were seeded from the old template and haven't been re-validated.

### Future vision (not this build)

- Stats weigh in on planning and **auto-assign** what to work on.
- **Re-plan every hour** when things haven't been done: re-evaluate when to do things, and **drop what there's no longer time for** ("lost that privilege").
- **"Pick 3" the evening before:** choose the three things that matter most tomorrow; the planner protects them.

---

## 11. v0.3 design conversation (2026-10-21)

**Confirmed working on the phone (v0.2):** site blocking, plan written into Google Calendar. Nothing reported annoying; Maven retimed some routine items. Inbox not useful (Snap notifications don't carry text) — remove it; opening the app from its notifications is good. Windows app and easier updates: not for a while.

**Sleep / wake:** Galaxy Watch → Samsung Health → Health Connect. Sleep showed no value because the watch wasn't worn. Split night sleep from naps (v0.2 sums them). "Wake up" should record the actual wake time; 5:30–6:00 is good, later is bad.

**Bedtime trigger** liked; wants an audible nudge too.

**Kinds of work (all "A"):**
- Multi-step tasks with attended and unattended phases (laundry; research prep → run → test). Planner books only attended minutes, fills the gaps, reminds when a run ends. Each step can be checked, or the whole thing marked done at once.
- Plan **7 days ahead**, not just today.
- Practice items (skills): weekly time target, min/max block, sprinkled into gaps; later learns real session lengths (15 min of magic tricks vs an hour of piano).
- **Skill tree, Skyrim-style**, user-editable: trees (Athletics, Magic, Music, Climbing, sports…) → skills (backflip, flair…) → time spent / progress. Sports and climbing can replace a workout, but only after the standard 3 workouts that week.
- Also: errands, waiting-on-someone, low-brain filler — liked.

**Priority:** ultimately driven by Stats (a low stat raises its area's baseline), with task-level override (option C). Until stats exist: manual area baseline + task override + bump.

**Do later / Not today / pick a day / something came up:** in the app only, not on the notification (misclicks). Neutral for now; **track the pattern** (what gets pushed, how often) for later.

**Skip + make up, generalized:** like ME 3411 for trading, any class or block can be skipped with "make up later", which creates a make-up task.

**Burnout:** nap rule liked (20 min before 3pm when sleep < 6h and energy low; walk/light task after 3pm). Recharge activities that work: walk, gym; maybe being bored to let things process, maybe art. Wants clearer options for "push vs reset".

**Blocking (Snapchat etc.):**
- Reply window from a notification: 5 minutes.
- Needs a way to pull Snap up instantly to record and send something.
- Never block or nudge during any call (the FaceTime-in-Snap block may have been a bug).
- "Still texting?" after the window, but longer real conversations must not get cut off.
- Emergency = instant, no passphrase. Credits keep the 60 s wait (it kills the urge to scroll).
- Wants all of this customizable so he can try different setups.

**v0.3 vs v0.4 split (agreed):** v0.3 = everything above except: stats (and stats-driven area importance), the Skyrim-style skill tree (built on practice time), learning real session lengths, burnout signals with push/reset prompts beyond the manual "How's today?", hourly re-plan that drops what no longer fits, patterns from deferrals, colour customization → v0.4.

**Future:** own workout and diet apps (with his Excel workout math) talking to this app; meal timing and food choice; catch-up / sneak-in workouts on busy weeks.


## 12. v0.3.1 (2026-10-21)

- Exam studying was doubling: seeded exam tasks (from the web app) plus the same exams in Google Calendar. **Direction (Maven): rely on Google Calendar, move away from the hard-coded import** so a version for other people isn't a big change. Done: new calendar mode **TESTS** (fixed time + auto study task, synced by event id); seeded exam studying, v0.3 keyword-made exam tasks and the seeded ME 3411 final removed by migration v4 (worked-on tasks kept); keyword spotting off by default.
- **Toward an app for other people:** the remaining Maven-specific seed (anchors, routine times, web-app tasks, goals/areas) should become an onboarding flow with neutral defaults. Not started; keep new features calendar- and settings-driven, not hard-coded.
- Block screen was slower in v0.3 (a 700 ms wait for notification taps) — removed; the block screen now closes itself if a reply window opens after it.
- Snapchat reply window "not working all the time": notification-tap detection depends on the app removing its notification on click. Added the robust rule: opening a blocked app within 15 min of a new message notification (category message/social/call, not story/streak) is a free reply window, once per message.


## 13. v0.4.0 (2026-10-21)

Built overnight on Maven's request ("add features discussed previously"); rollback = reinstall 0.3.1.
- **Stats v1** (`core/stats/Stats.kt`): Body (sleep, workouts, steps, meals), Mind (school+mind area minutes vs practice targets), Work (research/PT/career minutes vs 6 h/week), Hygiene, Discipline (worked ÷ planned task minutes per day), Social + Spirit (weekly 1–5 check-in). 7-day window, previous week for trend. Stat < 4 → +1 importance for its areas (`Stats.AREAS`), task override wins (setting `statsDriveImportance`). Area mapping still uses the seeded area ids — needs to become user-mappable for other people.
- **Skill trees** (`SkillTree`, `Skill`; migration v5 seeds Athletics/Climbing/Music/Magic). Time = sessions on the skill's practice task + manual minutes. Levels 1/5/15/40/100 h; child unlocks at parent level 1. Sport tree: 20+ min session checks the workout routine ("Sport" note).
- **Learning**: practice blocks capped at ~1.2× the median of the last 10 sessions (≥3 sessions).
- **Auto re-plan** (`Planning.autoReplan`, setting `autoReplan`): a TASK block that ended unworked after the plan was made → re-plan; dropped titles kept in `DayPlan.dropped`. `atRisk` = due ≤7 days and doesn't fit the week.
- **Burnout** (`Burnout.signals/shouldPrompt`): ≥2 signals after 8am, no mode chosen → one notification + How's today opens.
- **Guard**: VoIP calls count only with an active recording (fixes "in communication" false positives — prime suspect for Snapchat not blocking). Guard decision log (`guard/GuardLog.kt`, filesDir/guardlog.txt, 200 entries) shown in More. Free-window countdown notification. Message window 10 min.
- **UI**: bottom tab "You" = Routine | Stats | Skills; accent colors (`Settings.accent`); recharge ideas editor.
- Open: confirm Snapchat with the log; onboarding with neutral defaults for other users; text-brightness option.


## 14. v0.5.0 (2026-10-22)

**Maven's feedback on 0.4:** Snapchat had two separate issues: (1) the blocker sometimes didn't open (fixed in 0.3.1/0.4), (2) it blocked him *during a FaceTime call in Snap* (fixed by the call check). Both fine now; no change needed. Stats: **table most of it** until more data can be connected automatically; don't make him track lots of things weekly. Samsung Health sleep wasn't syncing. Skill tree should look more like Skyrim. Pages too long → more pages and choose which to show. Make it more universal (semester changes). Clean up redundancy; track universalization items for later. Asked whether Pick 3 auto-checks (it did, via hard-coded id).

**Built:**
- Navigation: `ui/Pages.kt` registry; bottom bar = `Settings.tabs` (≤4) + More; More = menu; settings split into section pages (`SetupScreen(section=…)`); Now cards hideable (`Settings.nowHidden`).
- `Semester` (AppData.semester), `Actions.setSemester/saveClass/deleteClass/endSemester/classProject`; Classes page. Archived projects' tasks excluded from planning (`AppData.liveTasks()`).
- `RoutineItem.autoCheck` (WAKE / WORKOUT / PICK3) replaces ids r-wake / r-workout / r-pick3. `Area.stat` replaces the hard-coded stat→area map. Migration v6.
- Priority folded: UI shows importance + "Firm deadline" (Priority.HIGH with a due date, which drives Heat). Calendar deadlines/tests still set HIGH.
- Health: per-permission reads, 3-day backfill, background-read permission, diagnostics panel.
- Constellation skill trees (`ui/SkillsScreen.kt`).
- Check-in off by default (`Settings.checkInEnabled`); Social/Spirit hidden unless on.
- Removed .ics + web-app import buttons; per-app rule list instead of Snapchat-only.

**Universal backlog (keep for the "app for other people" version):**
- Routine categories are a fixed enum (meal / hygiene / body / sleep / other). Stats and nudges key off them. Future: user-defined categories with a "counts as" mapping.
- Seed data is still Maven's (goals, areas, anchors, routine times, web-app tasks, research dates, starter skill trees) → onboarding with neutral templates.
- "Research session days" editor is tied to the seeded biosensor templates → generic "dated sessions".
- Workout notes list (lift, climb…) and sport detection (`SPORT_NOTES`) are fixed lists.
- Stats targets are fixed (work 6 h/week, mind ≥5 h) → per-stat targets.
- Recharge ideas / creed / emergency phrase are already settings.
- Day start/end times live in settings but have no editor yet.

**Redundancy still open (ask Maven):**
- Three ways to schedule recurring practice: a Habit weekly block, a PRACTICE task with a weekly target, a skill's "Plan practice". Could merge into one.
- "Pin as hot" vs "Bump for a week": both push a task up.
- "Capture an idea" on Now vs adding a task.


## 15. v0.6.0 (2026-10-25)

**Asked (Maven):** general to-do with levels (ASAP / set day / within days / try eventually); homework calendar (Canvas is mostly homework): 2 h default, mark longer ones from the weekly plan, auto-schedule, split big ones over days, **help get ahead and stay ahead** (he's not ahead now); drop non-installed default blocked apps from lists; block suggestions from usage (not built before); **settings lock**: urges to loosen a setting get tabled to the next morning with "are you sure"; YouTube-for-a-how-to problem: a "what are you doing?" prompt every ~5 min so writing "scrolling" makes the guilt land (not a phrase unlock, a report); running out of emergencies texting on Snap → more emergencies, but each needs the "I am choosing…" phrase typed; keep everything customizable.

**Built:**
- `Task.horizon` (ASAP / ON_DAY / WITHIN / SOMEDAY). ON_DAY/WITHIN use notBefore + dueAt; SOMEDAY excluded by `liveTasks()`; ASAP boosted in the planner. To-do page (`ui/TodoScreen.kt`).
- `CalMode.HOMEWORK`, `plan/Homework.kt`: sync (sourceKey `homework:uid`, est = `homeworkMin`, HIGH = firm, `targetAt` = due − `homeworkAheadDays`, `dailyCapMin`, notBefore = due − `homeworkLeadDays`), `Homework.ahead` meter. Scorer urgency = max(due, target). Planner per-day cap = max(cap, ceil(left / days to target)). Plan page "Due this day" + HowLong dialog. Migration v7 converts DEADLINES calendars and deadline: tasks.
- `store/Lock.kt` + `AppData.pending` (`PendingChange`, `ChangeKind`): loosening changes queued to `lockReleaseMin` next morning (≥6 h away); strict parts of a profile edit apply now. Now card + notification to confirm/keep.
- `BlockProfile.askPurpose / purposeCheckMin / purposeDailyMin`, `Ledger.purposeLog`, `Credits.purpose`; block-screen PurposePanel with today's answers. YouTube default on (migration).
- `BlockProfile.extraEmergencyPerDay`, `Credits.emergencyWithPhrase` / `phraseMatches`.
- `Actions.blockSuggestions` (≥ `suggestBlockMin` avg/day over 7 days), `Settings.notSuggest`. App lists show installed apps only.

**Ideas raised with Maven (not built):** purpose answers could feed a weekly "what YouTube was for" review; the lock could also cover deleting tasks/routine items; the homework meter could feed Discipline/Work stats; Snap reply-window length vs extra emergencies (maybe the reply window should be longer instead).


## 16. Workout app + what Not a Fraud must do with it (2026-10-26)

A separate workout app now lives in this repo: `workout-core/` + `workout/`, app ID `com.maven.workout`,
same signing key. Full doc: **`docs/workout/STATE.md`**. It replaces Hevy and Maven's workout spreadsheet
(PPL, RPE-chart weights from e1RM, 12-week block cycle) and **owns the split and the days per week**.

**Maven's decisions (2026-10-26):**
- Consistency first: **minimum 3 workouts/week**, full week 6. Days 1–3 Push / Pull / Legs (the minimum split),
  days 4–6 Chest & Back / Shoulders & Arms / Legs 2 — so climbing and other sports can be scheduled around
  smaller muscle days.
- The workout app decides the split and how many days; **Not a Fraud gets it pushed** and must pick it up.
- Not a Fraud **plans the full week (target, 6)** but **freaks out when the minimum (3) hasn't been or is
  projected not to be met** — e.g. Thursday with nothing done means Thu, Fri and Sat/Sun each need one; Not a
  Fraud must escalate when it's cutting that close.

**Contract (read it, don't re-derive it):** `content://com.maven.workout.sync/status` → JSON with `minPerWeek`,
`targetPerWeek`, `doneThisWeek`, `doneDates`, `trainedToday`, `daysLeft`, `needed`, `slack`, `risk`
(`MET` / `ON_TRACK` / `TIGHT` / `MUST_TODAY` / `MISSED`), `message`, `suggestedToday`, `inProgress`, `lastWorkoutDate`,
`injured`… and `/sessions?since=DATE`. Broadcast `com.maven.workout.CHANGED` (explicit to Not a Fraud's package)
when it changes. Guarded by signature permission `com.maven.permission.SYNC`. Details: workout doc § 5.

**Not a Fraud changes to build (next, in the Not a Fraud chat):**
1. Manifest: declare `<permission android:name="com.maven.permission.SYNC" android:protectionLevel="signature"/>`,
   `<uses-permission>` it, add `<queries><package android:name="com.maven.workout"/></queries>` (+ `.dev` for the
   debug pairing: NaF dev ↔ Workout dev, NaF ↔ Workout), and a manifest receiver for `com.maven.workout.CHANGED`
   with `android:permission="com.maven.permission.SYNC"`.
2. A `WorkoutLink` reader (Android side; parse into a `:core` model so the planner stays pure): read `/status` on
   app open, in the hourly worker, and on the broadcast. Store the last status in `AppData` (works offline;
   missing app = feature hidden).
3. **Planner:** replace the hard-coded lift anchors (Mon/Tue/Wed 5–6) with `targetPerWeek` workout blocks spread
   over the week (respecting class/anchors; workout length ~60 min, settable). Label each block with the
   workout app's suggestion for that day when known. Don't plan a workout block on a day already in `doneDates`.
4. **Minimum guard ("freak out"):** drive escalation from `risk` / `slack`:
   - `TIGHT` → a workout block is protected today (can't be pushed by "do later" without the settings-lock style
     confirmation); Now card turns amber.
   - `MUST_TODAY` → top of Now, nag ladder like time-sink nags (action = "Start workout" opens the workout app,
     Short mode is fine), routine-style "anger" escalation through the evening; earn-to-unlock credits could be
     paused until it's done (Maven to decide).
   - `MISSED` → one honest notification, no further nagging this week; counts against Discipline.
   - `inProgress` → treat as working (no nags, block shows "in progress").
5. **Routine auto-check:** the WORKOUT routine item (`RoutineItem.autoCheck = WORKOUT`) ticks from
   `trainedToday` instead of Health Connect guesses. The "sport counts after 3 standard workouts" rule should read
   `doneThisWeek` from here.
6. **Stats:** Body/Strength can read `/sessions` (count, minutes) and `streakWeeks`.
7. "Start workout" actions open the workout app (`com.maven.workout/.ui.MainActivity`).

**Answered:** climbing / other sports do **not** count toward the workout minimum (§ 17) — **but they do count as
cardio** (2026-11-02): a climbing/sport session should tick the daily cardio routine item and feed cardio in the
Body stat. Missed minimum → pause credit unlocks (§ 17).


## 17. v0.7.0 (2026-10-26)

**Maven:** homework not appearing in the plan. Wants one priority level on every task instead of importance + pin hot + bump, Pick 3 above them; drop "capture an idea"; map: hold to modify, tap expands, "+" on every expansion to add project/task; "+ Project / + Task" at the top of the list view; To-do as a tab on the Tasks page instead of under More; rename "List". Discuss what's tailored to him and what was guessed. Backlog: draw your own skill constellation. Integrate the workout app.

**Decisions (asked 2026-10-26):** priority = 4 named levels (Must/High/Normal/Low; stored as importance 5/4/3/2); sports do **not** count toward the workout minimum; when the minimum needs today's workout → **pause credit unlocks** until it's started (emergencies etc. still work).

**Homework bug:** the planner was fine (HomeworkPlanTest on seed-shaped data). The calendar reader dropped entries with availability FREE and calendars with VISIBLE=0 for every mode. Now only Events calendars filter those. All-day due date used the local date of UTC midnight (a day early). Calendar page shows per-calendar counts / sync state.

**Built:** `Importance` levels + `apply()` weights (Must ×2+30, High ×1.4+8, Low ×0.55; Pick 3 ×2+150); migration v8 (hot/bump → +1 level, ideas → SOMEDAY to-dos); `PriorityChips`; map `NodeType.ADD`, combinedClickable, expansion at every level; Tasks segmented tabs; `core/workout/WorkoutLink.kt` (`WorkoutStatus`, `mustToday`, `creditsPaused`, `receive` ticks the WORKOUT routine), `sense/WorkoutSync.kt` + `WorkoutChangedReceiver` (signature permission `com.maven.permission.SYNC`), workout Now card, hourly must-day nags, MISSED note, block-screen credit pause, planner workout label / skip trained days. `ChangeKind.WORKOUT_PAUSE`.

### What's tailored to Maven (must become onboarding / neutral defaults for other people)
- **Seed:** 6 goals, 14 areas with levels (research Must, school High) and stat mapping; weekly blocks (trading 8:30 weekdays, craft Fri 2–5 / Sat 8–12, date night Fri, Sunday review / CSWE / scout manual / off from 5); Fall 2026 classes + Thanksgiving; research session dates with biosensor templates; routine items and times; web-app tasks + review queue; catch-up study per class; starter skill trees; creed and emergency phrase; America/Chicago.
- **Name heuristics:** area guessing from project names (`Seed.areaFor`), course-number matching ("ME 3525"), Canvas / test calendar name guesses.
- **Apps:** default blocked list (Instagram, TikTok, X, Reddit, YouTube, Snapchat, Facebook…), focus apps (Kindle, Books, Anki, Libby, Acrobat, Samsung Notes), Snapchat quick-open and YouTube purpose profiles, desktop title keywords.
- **Fixed lists:** routine types (meal/hygiene/body/sleep/other), workout notes and sport names, stat names.

### What Claude guessed (Maven never set a number) — all adjustable unless noted
- Planner: day 7:00–21:30, blocks ≤ 90 min, 10-min breaks, rough day < 6 h sleep with a focus cap, Push 25/5, Recharge 50-min blocks / 4 h cap, priority weights. *(day hours, chunk and break have no editor yet)*
- Credits: start 15, 1 focus minute = 1 credit, max balance; emergency 5 min. *(no editor for start/max/emergency length)*
- Windows: still-texting 3 min, message window 10 min, quick-open 5 min × 6/day.
- Nudges: routine "angry" every 30 min, nag ladder timing, workout nags hourly from noon and louder after 6 pm.
- Stats: weights, targets (work 6 h/week, mind ≥ 5 h), "low" < 4, burnout thresholds. *(no editor)*
- Skills: levels at 1/5/15/40/100 h, sport counts at 20+ min. *(no editor)*
- Homework: 2 days ahead, 2 h/day cap, 10-day lead, 90-min blocks (2 h default was Maven's). Tests: 6 h study, 10-day lead.
- Lock release 7:00 (≥ 6 h away); purpose 5 min / 60 min a day; 5 typed extra emergencies; suggest blocking at 20+ min/day.

### Backlog (Maven, 2026-10-26)
- **Draw your own constellation:** a simple grid to sketch a shape per skill tree; it fills in denser as more skills are earned (stars = earned skills placed on the drawn lines).
- Onboarding with neutral templates (replaces the seed above); editors for the "no editor" items.
- User-defined routine types with a "counts as" mapping.
- Workout: "climbing tomorrow" planning via the workout app's muscle data; Body stat from `/sessions`.


## 18. v0.8.0 (2026-10-26)

**Maven's screenshots:** Google Calendar shows "HW6 [MECH_ENG-3525-…]" as an all-day Canvas entry (Busy), but Not a Fraud's diagnostics said Canvas: 0 entries, hidden, **NOT SYNCING** (same for another calendar). So the provider's `SYNC_EVENTS` = 0: the Google Calendar app displays it from the web, but nothing reaches CalendarContract. Sleep from Samsung Health also still missing (no diagnostics screenshot yet).

**Answers:** (1) settings page for the guessed numbers now; (2) yes, the workout routine item stops having its own days/week.

**Built:** `PhoneCalendar.enableSync` (sets `SYNC_EVENTS` = 1 and `VISIBLE` = 1 on the Calendars row, which apps are allowed to write, then `ContentResolver.requestSync` expedited for the account). Auto-runs once per process for non-Events, non-Ignore calendars that aren't syncing; button on the Calendar page. Health permission request split (`Health.BASE`, then `BACKGROUND`). New settings `roughFocusCapMin`, `stillTextingMin`, `messageWindowMin`, `angryRepeatMin`, `workTargetMin`, `mindTargetMin`, `sportMin` and a Numbers panel (More → Planning) through `Lock.setNumber` (`ChangeKind.NUMBER`: raising still-texting / message window / credit cap / emergency length / angry interval waits for the morning). `WorkoutLink.receive` sets the WORKOUT routine item's weeklyTarget to `targetPerWeek`; Health exercise doesn't tick it while the workout app is connected.

**Open:** if sleep still fails, get a screenshot of More → Calendar & health → Samsung Health (permissions line + records list).

## 19. Status check (2026-10-28): what works, what's left

**Confirmed on the phone:** Canvas homework now reaches the plan (0.8 sync switch-on fixed it).

**Sleep still failing.** Diagnostics: all four Health Connect permissions granted, steps arrive (2,742 read while Samsung Health showed 3,828), but **0 sleep records in Health Connect** in 3 days, while Samsung Health shows nights on the 22nd, 25th, 26th, 27th (none last night: watch not worn). So the Samsung Health → Health Connect link carries steps but not sleep: Not a Fraud can't read what isn't there. Next checks are on the phone: Health Connect → data → Sleep (empty?), Health Connect → App permissions → Samsung Health → write Sleep on. Fallback if Samsung won't export sleep: Samsung Health Data SDK (developer mode on the phone), or keep wake time from first unlock.

**New requests (Maven):**
- Two-step confirmation: changing a normal setting pops up "change X from A to B?", so scrolling can't change things by accident.
- Explanations around the app for first-time users → part of the next phase: group everything still personalized, plan making the app ready for other people, design onboarding, then make it universal.

**Everything still to add or change (for the next conversation):**
1. Sleep from Samsung Health (above).
2. Two-step confirm on settings (scope to decide: every setting on More pages, or only switches/chips).
3. Next phase, "ready for other people": the § 17 list of tailored and guessed things; in-app explanations; onboarding flow; user-defined routine types with a "counts as" mapping; "research session days" → generic dated sessions; fixed lists (workout notes, sports, stat names) → editable; neutral starter templates; Workout app presets for other people.
4. Skill trees: draw your own constellation on a grid, filling in as skills are earned.
5. Workout link, rest of the spec (§ 16): on a TIGHT day the workout block can't be pushed with "do later" without confirmation; a MISSED week counts against Discipline; "climbing tomorrow" planning from the workout app's muscle data; Body stat from workout sessions.
6. Stats (tabled until more is automatic): feed Work/Discipline from the homework meter, Body from workouts, screen time trends.
7. Recurring practice has three ways to set up (Habit weekly block, practice task with weekly target, a skill's "Plan practice"): merge or keep?
8. Blocking: "what are you doing?" for websites (YouTube in a browser skips it today); weekly review of purpose answers; settings lock covering deleting tasks / routine items; Snap: longer reply window vs typed extra emergencies.
9. Text brightness / contrast option.
10. Old items: review of the stale web-app tasks; trading vs ME 3411 time clash; confirm the Samsung "never sleeping" setup step.
11. Deferred by Maven: PC/phone sync (Firebase) + Windows app, easier updates (Obtainium), diet app, meal timing.
12. Workout app's own backlog (docs/workout/STATE.md § 6): Hevy import, warm-up / plate calculator, rest timer notification, write workouts to Health Connect, edit finished workouts, supersets, weekly volume.


## 20. The app family + Not a Twig (diet) (2026-11-02)

Maven's apps are all "Not a _": **Not a Fraud** (this app), **Not a Troglodyte** (workout, app ID
`com.maven.workout`, renamed from "Workout" in 0.1.2), **Not a Twig** (diet, `com.maven.diet`, new — full doc
**`docs/diet/STATE.md`**). All three live in this repo, share the signing key and the signature permission
`com.maven.permission.SYNC`.

Not a Twig serves `content://com.maven.diet.sync/status` (kcal/macros eaten vs target, water, weighedToday,
trend, fasting window state, hours since food, slots logged, overdue **nags** with level 1–3, today's clarity /
energy check-in) and broadcasts `com.maven.diet.CHANGED` to Not a Fraud.

**Not a Fraud to-do from this (diet doc § 4):** read the diet status like the workout status (add
`<queries>` for `com.maven.diet` / `.dev` and a receiver for `com.maven.diet.CHANGED`); route Twig's meal /
water / weigh-in nags into the routine "anger" so there's one nag system; tick meal routine items from
`slotsLogged`; show clarity/energy in "How's today"; later hand Twig sleep hours for bad-day help and insights.
Sports/climbing → cardio (§ 16).


## 21. v0.9.0 (2026-11-03): Not a Twig link, streaks, sports → cardio

**Asked (Maven):** update Not a Fraud for what changed in Not a Troglodyte and Not a Twig; add a routine type for
streaks that count automatically unless he says he broke them.

**Built:**
- `core/diet/DietLink.kt` (`DietStatus`, `SyncNag`: Twig's contract with defaults), `sense/DietSync.kt` +
  `DietChangedReceiver` (manifest `<queries>` for `com.maven.diet` / `.dev`, receiver for `com.maven.diet.CHANGED`
  guarded by `com.maven.permission.SYNC`). Read on app open, hourly, on the broadcast, and on the routine alarm
  (throttled 4 min; `RoutinePolicy.DIET_POLL_MIN` = 30 keeps an alarm coming while Twig is connected).
- `RoutineItem.dietSlot` links a MEAL item to a Twig slot (migration v9 links by name: Lunch, Supper,
  "Pre-workout meal" → Pre-workout). Linked + connected + `Settings.dietNags`: `slotsLogged` ticks the item
  (note "Not a Twig"); its mood comes from Twig's nag level (1 = late, 2–3 = angry), not the item's clock.
  Twig's other nags (water, weigh-in, check-in, unlinked slots) become alerts with ids `twig:<key>`; the
  notification opens Twig instead of "Done". Twig 0.2.1 adds `notifyHere` so its own notifications can be off
  while still sending the list (its `nags` switch empties the list).
- How's today shows Twig's line; clarity or energy ≤ 2 is a burnout signal and makes `DayAdvisor` suggest
  recharge (before 3 pm) / reset with basics first (eat if 5 h+ since food in window, water if > 1/3 behind).
- `AutoCheck.CARDIO` (r-cardio in v9). Sport-skill sessions tick Cardio; they tick Workout only when
  Troglodyte isn't connected. With Troglodyte connected, Samsung Health exercise ≥ `sportMin` ticks Cardio.
  Body stat gains "Cardio (incl. sports)". "Workout (lift, climb...)" renamed "Workout". UI says Not a Troglodyte.
- `RoutineCategory.STREAK` + `RoutineItem.streakSince` + `RoutineDay.broken`: done every applicable day by
  itself, no times / nags / plan time; `RoutinePolicy.streak/bestStreak/streakHeld`; Routine row shows the count
  with "Broke it" (today / yesterday) and Undo; editor takes "days in a row so far".

**Open / to check:**
- ~~7:00 pre-workout meal vs Twig's 4:30 slot~~ → 0.9.1 meal sync below.
- A lift logged on the watch as Samsung Health exercise ticks Cardio: **Maven is fine with it** (random runs
  ticking it is the point: the more automatic, the more consistent).
- Still from § 16/19: TIGHT-day protection of the workout block, MISSED → Discipline, Twig sleep hand-off.

### v0.9.1 (2026-11-03): one meal schedule

Maven: "if someone only has one or the other app, it should work independently, but if they have both, they
need to auto sync together as best possible." Twig 0.2.2's status carries `slots` (name, `by`) and the eating
window; its provider accepts `update content://…diet.sync/meal` (old / name / by / remove, `MealSync.apply`,
write permission = SYNC). `DietLink.syncMeals`: each timed slot ↔ one MEAL item (by `dietSlot`, else by name),
due = slot time, keeping the item's lead-in and anger gaps; a new slot creates an item; meals without a slot get
`pausedByDiet` (off, listed on Routine); `disconnect` turns them back on. Editing a linked meal here pushes to
Twig first (`DietSync.pushMeal`), deleting removes the slot; a schedule change from Twig re-plans.


## 22. v0.10.0 – v0.11.0 (2026-11-03/04): the brick ladder and the § 19 backlog

**Maven (2026-11-03):** sleep now arrives from Samsung Health (it needed time to sync; § 19 item 1 done). Build
everything from the § 19 list except "ready for other people" and phone/PC sync. Brick the phone progressively;
bedtime bricks it; no NFC key for now; his own morning routine turns grayscale on and he turns it off when
needed (keep it). Level 3 apps are entered by hand with suggestions. Lock level: **Medium**. Practice: **merge
into one**. Trading stays at 8:30 during ME 3411: skip-and-make-up for several times / until a date. Snap: keep
typed extra emergencies. Lifts logged on the watch ticking Cardio: fine (the more automatic the better).

**0.10.0 built:**
- `guard/Ladder.kt`: points from today only (slipped blocks ≤3, angry routine ≤2, blocked-app minutes 45/90/150
  → 1/2/3, must-workout 1–2; −2 for 25+ min of work in 90 min; running session = 0). 2/4/6 → levels 1/2/3, capped
  by `LadderSettings.maxLevel`; Level 3 only once `allowlistConfirmed`; protected time caps at 1. Bedtime
  (`bedMin`–`wakeMin`) = Level 3 + `bedtimeExtra`. Effects: credit wait ×2 / ×3, purpose every ½, credits frozen
  at 3 and bedtime, grayscale from `grayscaleFrom` (only with WRITE_SECURE_SETTINGS; only undoes grayscale it set),
  unlock → Now from `unlockToNowFrom`, `GuardPolicy` blocks non-allowlisted apps at 3 (`GuardDecision.brick`),
  climb notification, home widget (`ui/NowWidget`), optional lock-screen wallpaper (`notify/LockWallpaper`),
  bedtime start/end alarms. Allowlist suggestions: `Ladder.ESSENTIALS` + 7-day usage. Page: More → Brick ladder.
- Confirm dialog on every settings control (`ui/Confirm.kt`); `PendingChange.reason` asked when a loosening
  change is queued and shown in the morning. Routine delete / switch-off go through the lock (`ROUTINE_REMOVE`).
- Medium lock: `SettingsGuard` + GuardService reads Settings / package-installer windows (guard description
  updated honestly); NaF's own pages with Uninstall / Force stop, or a switch for it that's on, step back to a
  block screen offering a 10-min morning window (`SETTINGS_WINDOW`). Turning things on is never stopped.
  `guard/SecureSettings.kt`: optional `adb shell pm grant … WRITE_SECURE_SETTINGS` → grayscale + guard self-repair.
- `Actions.skipAnchorMany` (next N / until date, make-up optional).

**0.11.0 built:** one practice type (`Task.fixedDays/fixedStartMin/fixedLenMin`; migration v10 turns yielding
HABIT anchors into practice tasks; fixed slots count toward the weekly target); tight-day workout block survives
soft days; Discipline = plan followed + blocked-app minutes + homework lead, −2 for a missed workout week; Body
counts Troglodyte's `doneDates`; `WorkoutLink.sportOn` → Troglodyte hint; site subjects use their app's profile
(`AppRules.SITE_APPS`, YouTube in Chrome asks "what for?"); `PurposeReview` panel + Sunday note; text contrast;
draw-your-own constellation (`SkillTree.shape`, `ShapeEditor`, `DrawnConstellation`).

**Other apps the same round:** Not a Twig 0.3.0 (sleep, meal planner + groceries, barcode, weekly review,
experiments — `docs/diet/STATE.md`), Not a Troglodyte 0.2.0 (Hevy import, warm-ups + plates, rest notification,
Health Connect write, edit finished, supersets, weekly volume, climbing tomorrow — `docs/workout/STATE.md`).

**Still open from § 19 (not asked for now):** "ready for other people" (onboarding, neutral templates, editable
fixed lists), phone/PC sync, Obtainium. Samsung "never sleeping" step still unconfirmed. Stale web-app task review
exists (Now → review queue). Ideas: NFC key for the ladder; ladder tuning after a week of use.


## 23. v0.12.0 (2026-11-03): routine items open an app on unlock

Maven: "a way for routine items to automatically open apps as the brick level adds up… when I need to read the bible
per my routine, make it open the app when my phone is unlocked. Just build the feature, nothing set up yet."
`RoutineItem.openPkg / openFromStage (0 due, 1 late, 2 angry) / openAtLevel / openEveryMin / openDoneMin`;
`RoutineDay.opened / appMin`. `RoutinePolicy.appToOpen` (angriest first, throttled, not after quiet time); GuardService
on USER_PRESENT launches it (takes precedence over unlock → Now); `Actions.routineAppTime` from `leave()` adds the
minutes and ticks at `openDoneMin`. Routine-open apps are always allowed at Level 3 / bedtime (`Ladder.allowedAtTop`).
No seeded items use it.


## 24. v0.12.1 (2026-11-09): guard switched on but dead

Maven: blocking stopped; the "why" log hadn't updated since Sunday ~2 pm. The accessibility switch stayed on but the
service wasn't running/hearing events; `Perms.accessibility` only reads the switch, so the hourly worker said nothing.
Fix: `GuardService` catches every event / receiver / timer error (`guardError` → `CrashLog` + GuardLog) instead of
crashing the process; `CrashLog` also installs a default uncaught handler (filesDir/crashes.txt, last 8);
`GuardHealth.state` = OFF / NOT_RUNNING (instance null) / NOT_HEARING (no event for 20 min while UsageStats shows an
app resumed) / RUNNING; hourly check restarts via WRITE_SECURE_SETTINGS (remove + re-add the component) or notifies;
status panel with Copy errors on Blocking & credits, red on Now when not running. Root cause still unknown: next step
is the copied error text if it happens again.


## 25. v0.13.0 (2026-11-09): streak numbers; Phase 1 of going public (PUBLIC.md)

Maven: add a small streak number to each routine item. Keep the three apps separate. Start the next steps (PUBLIC.md
phases).
- `RoutinePolicy.consistency` (days in a row over applicable days; weekly items = weeks meeting target; today counts
  once done) → number on each Routine row (green when it's the best run).
- `Module` (PLANNER, ROUTINE, BLOCKING, STATS, SKILLS) in `Settings.modules`; pages hidden via `moduleOf` / `moduleOn`;
  ROUTINE off silences `alertsDue`, BLOCKING off makes `GuardPolicy` ignore and the ladder 0. `Lock.setModule`
  (Blocking / Routine off wait for the morning: `ChangeKind.MODULE_OFF`). More → Modules.
- `seed/Templates.kt`: `fresh` (empty, `onboarded = false`, phone's zone) is the new-install seed on Android and desktop;
  `apply(Answers)` builds goals/areas (stat per goal), a Life area/project, starter routine from wake/bed offsets,
  a Work block, Sunday review, tabs, ladder bed/wake. `Settings.onboarded` defaults to true so existing saves skip it.
  `ui/Onboarding.kt`: the flow, permission steps (accessibility = Play prominent disclosure with consent checkbox +
  sideload "restricted settings" hint), `ModulesScreen`, `TIPS` / `PageTip`. A test checks no Maven strings end up
  in a new setup. `Seed` (Maven's data) is still in the app for tests and old-save migrations: move it out of the
  release build before publishing (next round). Emulator tests load `Seed` via `TestData.loadFull()`.


## 26. Feedback mode (2026-11-10; NaF 0.13.1, Troglodyte 0.2.2, Twig 0.3.1)

Maven wants to go through the apps and note tweaks in the moment. New shared Android library `:feedback`
(`com.maven.feedback`, first module shared by all three apps): `FeedbackButton` (draggable small FAB, BoxScope,
PixelCopy screenshot of the window, then a comment dialog), notes saved to filesDir/feedback (`<stamp>_<page>.txt` +
half-size png), `FeedbackSettings` (switch, count, Share → zip notes.md + pngs via its own FileProvider
`${applicationId}.feedback`, Clear). Maven shares the zip into chat (decided over pulling from the repo: no GitHub
token on the phone). Pages: NaF tab id, Troglodyte/Twig route class name or tab. The library is where shared
onboarding / tips / crash log should go next (PUBLIC.md).

**2026-11-11:** GitHub warned Actions storage hit the 0.5 GB monthly allowance (private repo). It was 90 old build
artifacts (1.8 GB: debug APKs from every CI run, release APK/MSI hand-offs). Deleted them all; CI no longer uploads
the debug APK, release hand-off artifacts keep 1 day. Releases and `ci-logs/*` branches don't count toward it.
Feedback share now sends notes .md + pngs as separate files (zip optional).

## 27. v0.14.0 (2026-11-11): day feedback and the unlock check-in

From the first feedback-mode notes (2026-11-10): "I am done with my lab… hit done and it says 'you can fill in that
extra time with ___'"; "I went over on trading… reschedule other things, get rid of some of the fun stuff";
"a popup when my phone unlocks… a way to say what I've been working on"; "there's a lot of black, I should feel
more guilty… and rewarded"; "I need to study for my test Thursday… the app should say 'you need to work on this'";
"is Start starting supper?"; lock screen should show routine to log, next thing, missed things. From Twig's notes:
bricking should revolve around weigh-in / eating / Bible, with inputs on the unlock screen, so things get entered
when they happen instead of a pile at night.

Decisions (Maven, 2026-11-11): check-in on **every unlock while something's overdue, Later = 20 min, 3 Laters in a
row = 1 ladder point**; when you go over, **least important first** (fun / low-importance move to coming days;
hot deadlines and exams never; protected time only if you say so); day feedback = **all four** (plan shows planned
vs actual, day score on Now, priority nag, end-of-day summary).

- `core/plan/DayReview.kt`: `mark(block)` DONE / OTHER / PARTIAL / MISSED / EXCUSED; `missed()` (now the ladder's
  "slipped" count, via `DayAdvisor.skippedBlocks`); `totals`; `score(date)` 0–100 = work 50 % (worked ÷ planned task
  minutes; past days use `DayContext.plannedTaskMin`), routine 30 %, blocked apps 20 % (2 h = 0), parts with nothing to
  measure drop out; `goodStreak` (Settings.goodDayScore 70); `priorityNag` (HIGH / hot / importance ≥ 4 / exam study,
  due ≤ 4 days, < 30 min in 3 days; shown after an hour elsewhere or after noon); `summary` / `summaryDate`
  (evening from dayEnd − 1 h; yesterday's until noon, until `DayContext.summarySeen`); `fill` (best task for freed
  minutes); `squeeze` (hot / exam work due ≤ 36 h not placed today + protected blocks still ahead).
- `core/guard/UnlockCheckIn.kt`: prompts ROUTINE (late / angry; Open it for `openPkg`), MEAL and WEIGH (only when
  Twig says `acceptsQuick`), BLOCK (missed in the last 6 h, max 3), PRIORITY. `AppData.checkIn` (`CheckInState`:
  snoozeUntil, laters, laterDate, handled keys per date). `due()` false at bedtime, when switched off
  (`Settings.checkInOnUnlock`, Medium-lock protected via ChangeKind.CHECKIN), or nothing to ask. Ladder +1 point
  "check-in put off N times" while `ignoring()`.
- Excuses: `DayContext.excused` (`Excuse(start, end, reason, counts)`); "Nothing, honestly" is `counts = false`
  (answered, still missed); a reason marks the block EXCUSED.
- Backlog: `Actions.logPast(taskId | title, start, end)` (trims against logged sessions, earns credits, counts sport).
  Ending a fixed block early: `DayContext.endedEarly[DayPlanner.endKey(block)]`, planner trims that block, re-plan.
- Android: `ui/DayFeedback.kt` (LogTimeDialog, DayScoreCard, SummaryCard, SqueezeCard, CheckInSheet); Now buttons
  relabelled by block kind; Plan colours past task blocks and taps open "what did you do?"; GuardService on
  USER_PRESENT opens MainActivity with `EXTRA_CHECKIN` (after the routine-app open, before ladder → Now); lock
  wallpaper adds to-log routine, missed blocks and next.
- Twig 0.4 provider writes: `/weigh` (lb, at), `/quickmeal` (slot, text, at, usual). DietStatus adds `usual`
  (slot → label) and `acceptsQuick`.
