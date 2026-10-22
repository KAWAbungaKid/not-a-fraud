# Not a Fraud — setup

About 20 minutes, once. You don't need Android Studio for any of this; it's only for development.

## Phone (Galaxy S26 Ultra)

### 1. Install

1. On the phone, sign in to GitHub in Chrome and open
   **github.com/KAWAbungaKid/not-a-fraud/releases**.
2. Under the newest release, tap **not-a-fraud-x.y.z.apk** to download it.
3. Open the download. Android asks to let Chrome install apps: **Settings → allow from this source**, go back, **Install**.
4. If Play Protect warns about an unknown app, tap **More details → Install anyway**. (It's unknown because it's yours and not on the Play Store.)

### 2. Allow restricted settings (do this before anything else)

Android blocks sideloaded apps from becoming accessibility services until you allow it.

1. Long-press the Not a Fraud icon → **App info**.
2. Top-right **⋮** → **Allow restricted settings** → confirm with your PIN/fingerprint.

If you don't see that menu item, try step 3 first; the greyed-out switch will tell you to do this.

### 3. Work down the Setup tab

Open the app → **More** (bottom right). Each row has a **Fix** button that jumps to the right Settings screen. Go top to bottom until it says 8/8.

| # | What | What to tap |
|---|---|---|
| 1 | Notifications | Allow |
| 2 | Guard (Accessibility) | Installed apps → **Not a Fraud guard** → On → Allow |
| 3 | Usage access | Not a Fraud → On |
| 4 | Message mirror | Not a Fraud message mirror → On |
| 5 | Health Connect | Allow sleep, exercise, steps |
| 6 | Calendar | Allow |
| 7 | Exact alarms | On |
| 8 | Battery: unrestricted | Allow |

**Then the Samsung step the app can't check (row 9):**
(If Not a Fraud doesn't appear in the Never sleeping list, that's expected once battery is set to Unrestricted — the app's watchdog will tell you if it ever gets stopped.)
Settings → **Battery** → **Background usage limits**:
- Remove Not a Fraud from **Sleeping apps** and **Deep sleeping apps**.
- Add it to **Never sleeping apps**.
- Turn off **Put unused apps to sleep**.

Without this, One UI kills the guard after a few hours and blocking silently stops. The app will
post a "Guard was stopped" notification if that happens.

**And make Samsung Health write to Health Connect:**
Samsung Health → ⋮ → **Settings → Health Connect** → allow it to write **Sleep**, **Exercise** and **Steps**.

### 4. Google Calendar

1. On a computer, open **calendar.google.com** → left side **Other calendars → + → Create new calendar** → name it **Not a Fraud** → Create.
2. On the phone wait a minute for it to sync, then **More → Google Calendar** → tap **Not a Fraud** under "Tap to write plan here".
3. Under "Each calendar is…", set every calendar: **Events** (fixed time to plan around), **Deadlines** (Canvas: each entry becomes a task due then), or **Ignore** (old routine calendars, holidays). Canvas-looking calendars start as Deadlines.

From then on the day's plan (tasks, blocks, routine reminders) appears in the Not a Fraud calendar and updates as the plan changes. Don't edit those events in Google Calendar; change things in the app.

### 5. Pick your apps

Setup → **Blocking**:
- **Blocked apps** — starts with Instagram, TikTok, X, Reddit, YouTube, Snapchat, Facebook. Untick anything you don't have or want.
- **Focus apps that earn credits** — starts with Kindle, Play Books, Anki, Libby, Acrobat, Samsung Notes. Add whatever you read or study in.
- **Blocked websites** — instagram.com, tiktok.com, x.com, twitter.com, reddit.com, youtube.com, facebook.com. Works in Chrome, Samsung Internet, Brave, Edge, Firefox, Opera and DuckDuckGo.
- **Mirrored into Inbox** — Snapchat. Its messages show up in the Inbox tab, with reply when Snapchat offers it.

You start with 15 credit minutes.

### 6. Quick Snap tile

Pull the notification shade down twice → pencil (edit) → drag **Quick Snap** into your tiles. Tapping it opens Snapchat free for 5 minutes, 6 times a day.

### 7. Check it works (2 minutes)

1. Open Instagram → the block screen should appear.
2. Tap **10m** → Instagram opens. After 3 minutes you get the first nudge.
3. Pull down the notification shade → the "do this now" line is there.

If the block screen doesn't appear, re-check row 2 (and the restricted-settings step).

## How updates work

Claude pushes code to GitHub; GitHub builds a signed release. To update:
open the releases page on the phone, download the new APK, install. It installs over the old one
and keeps your data and permissions (same signing key).

Later option: **Obtainium** (free, open source) can watch the repo and update with one tap. Since
the repo is private it needs a GitHub token pasted in once; we'll set that up when updates get
frequent.

## Windows PC

1. On the PC, open the same releases page and download **not-a-fraud-windows-x.y.z.msi**.
2. Run it. SmartScreen may say "Windows protected your PC": **More info → Run anyway** (unsigned installer).
3. Not a Fraud appears in the Start menu and as a tray icon (bottom-right, maybe under the ^ arrow).
4. In its **Settings** tab, turn on **Start with Windows**.

The PC app tracks which program is in front (and YouTube etc. by window title), counts active time
only (5 min without input = idle), nudges you when a distraction stays in front, and can minimize
it after the second nudge if you turn that on. Phone and PC keep separate data until sync is
switched on.

## For development only: Android Studio

1. **File → New → Project from Version Control** → `https://github.com/KAWAbungaKid/not-a-fraud`.
2. Let Gradle sync finish (first time downloads a lot).
3. Phone: Settings → About phone → Software information → tap **Build number** 7 times →
   Developer options → **USB debugging** on. Plug in, accept the prompt.
4. Pick the **android** run configuration and press Run. This installs **NaF dev**
   (`com.maven.notafraud.dev`) next to the real app, with its own separate data.
