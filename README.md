# Not a Fraud

Personal productivity system: blocks time-sink apps with earn-to-unlock credits, keeps a live
"do this now" line in the notification bar, generates the day's plan from tasks + calendar +
fixed anchors, and nudges (escalating, never shaming) when a time-sink stays open.

- `core/` — pure Kotlin, no Android: models, scoring engine, day planner, credits, nags, ICS, store. Unit tested.
- `android/` — Kotlin + Jetpack Compose app. Accessibility service is the persistent host.
- `desktop/` — Compose Desktop tray app for Windows (JNA foreground tracking).

**Install / setup:** [docs/SETUP.md](docs/SETUP.md) · **Design + status:** [docs/STATE.md](docs/STATE.md)

Builds: every push runs CI (core tests, APK, Windows MSI, emulator smoke test). Tags `v*` publish a
GitHub Release with the signed APK and MSI.
