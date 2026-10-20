# Working on this repo (notes for Claude sessions)

- Read `docs/STATE.md` first: product intent, design decisions, open questions.
- The dev sandbox usually can't reach Google Maven / Gradle, so **CI is the compiler**. Push to `main`,
  then read logs from the `ci-logs/<job>` branches (jobs: core, android, desktop, emulator,
  release-apk, release-msi): `git fetch origin ci-logs/android && git show FETCH_HEAD:log.txt`.
  GitHub's log-download URLs are blocked from the sandbox; this is the workaround.
- `ci.yml` has `cancel-in-progress`: pushing again cancels the running emulator job. Batch changes.
- Toolchain (verified 2026-10-20): Gradle 9.8.2, AGP 9.4.1 (built-in Kotlin: do NOT apply
  `org.jetbrains.kotlin.android`), Kotlin 2.4.21, compileSdk 37, minSdk 34, Compose BOM 2026.09.00,
  Compose Multiplatform 1.12.2. `.github/workflows/probe.yml` prints latest versions.
- Keep `core/` free of Android imports — the desktop app depends on it.
- Release: bump `appVersionName` / `appVersionCode` in `gradle.properties`, add
  `docs/releases/vX.Y.Z.md`, push, then dispatch the release workflow
  (`gh api -X POST repos/KAWAbungaKid/not-a-fraud/actions/workflows/release.yml/dispatches -f ref=main`).
  Pushing tags from the sandbox is refused by the proxy; the workflow creates tag `v<appVersionName>` itself.
  It also mirrors the APK + MSI to branch `ci-logs/dist` so they can be fetched with git.
- `gh` GraphQL is blocked here; use `gh api` REST calls.
- Emulator job boots the emulator by hand (the reactivecircus action stopped booting after the
  Oct 2026 runner image change); boot output is in the emulator log.
- Signing key is `keystore/notafraud.jks` (password in `android/build.gradle.kts`). Never replace it:
  a different key means updates won't install over the existing app.
