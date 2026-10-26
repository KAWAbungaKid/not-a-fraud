# Not a Fraud (formerly Project Max) — state of the build

Living doc. Read this first in any new chat about the app. Last updated 2026-10-26 (workout app added — § 16 and `docs/workout/STATE.md`).
Repo: **github.com/KAWAbungaKid/not-a-fraud** (private). App ID `com.maven.notafraud`.

---

## 0. Where things stand (2026-10-25, v0.6.0)

v0.6.0: to-do list, homework calendars with work-ahead, settings lock, purpose mode, typed extra emergencies, block suggestions (section 15). v0.5.0: pages + choosable bottom bar, Classes/semester, constellation skill trees, Health fixes + diagnostics, less hard-coding (section 14). v0.4.0: stats, skill trees, auto re-plan, burnout prompt, weekly check-in, guard log, stricter call check, accent colors (section 13). Earlier:

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

**Open questions for Maven:** do climbing / other sports count toward the minimum of 3? (Currently no.) Should a
missed minimum lock credits/apps, or only nag?
