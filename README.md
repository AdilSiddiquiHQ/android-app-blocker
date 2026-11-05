<div align="center">

# App Blocker

**A rootless Android app blocker in ~1,600 lines of Kotlin.**<br>
Accessibility-driven enforcement, a full-screen block screen, focus sessions,<br>
cross-midnight schedules, domain blocking, and Reels/Shorts filters.

No backend. No analytics. No `INTERNET` permission.

<br>

[![Platform](https://img.shields.io/static/v1?label=Platform&message=Android%208.0%2B&color=3DDC84&logo=android&logoColor=white)](#requirements)
[![Min SDK](https://img.shields.io/static/v1?label=minSdk&message=26&color=3DDC84&logo=android&logoColor=white)](#requirements)
[![Target SDK](https://img.shields.io/static/v1?label=targetSdk&message=36&color=3DDC84&logo=android&logoColor=white)](#requirements)
[![Kotlin](https://img.shields.io/static/v1?label=Kotlin&message=2.2.21&color=7F52FF&logo=kotlin&logoColor=white)](#requirements)
[![AGP](https://img.shields.io/static/v1?label=AGP&message=8.13.2&color=3DDC84&logo=gradle&logoColor=white)](#requirements)

[![Build](https://github.com/AdilSiddiquiHQ/android-app-blocker/actions/workflows/android.yml/badge.svg)](https://github.com/AdilSiddiquiHQ/android-app-blocker/actions/workflows/android.yml)
[![Last commit](https://img.shields.io/github/last-commit/AdilSiddiquiHQ/android-app-blocker?color=7C5CFF)](https://github.com/AdilSiddiquiHQ/android-app-blocker/commits/main)
[![Stars](https://img.shields.io/github/stars/AdilSiddiquiHQ/android-app-blocker?style=flat&color=7C5CFF)](https://github.com/AdilSiddiquiHQ/android-app-blocker/stargazers)
[![Issues](https://img.shields.io/github/issues/AdilSiddiquiHQ/android-app-blocker)](https://github.com/AdilSiddiquiHQ/android-app-blocker/issues)
[![Repo size](https://img.shields.io/github/repo-size/AdilSiddiquiHQ/android-app-blocker)](https://github.com/AdilSiddiquiHQ/android-app-blocker)
[![License](https://img.shields.io/static/v1?label=license&message=MIT&color=blue)](LICENSE)

[![No INTERNET](https://img.shields.io/static/v1?label=INTERNET%20permission&message=none&color=critical)](#privacy)
[![Dependencies](https://img.shields.io/static/v1?label=runtime%20deps&message=2&color=informational)](#dependencies)
[![PRs](https://img.shields.io/static/v1?label=PRs&message=welcome&color=7C5CFF)](#contributing)

</div>

---

## Table of contents

- [Why this exists](#why-this-exists)
- [Features](#features)
- [What is enforced, exactly](#what-is-enforced-exactly)
- [How it works](#how-it-works)
- [Project layout](#project-layout)
- [Quick start](#quick-start)
- [Permissions](#permissions)
- [What it cannot do](#what-it-cannot-do)
- [Privacy](#privacy)
- [Dependencies](#dependencies)
- [Development](#development)
- [FAQ](#faq)
- [Contributing](#contributing)
- [License](#license)

---

## Why this exists

Most "app blockers" on Android fall into three buckets: they need root, they
route your traffic through a VPN they operate, or they are a timer bolted onto
Digital Wellbeing that you can swipe away. This project takes the boring,
permission-based route that actually works on an unrooted phone:

- An `AccessibilityService` sees which package came to the foreground.
- If that package is on your block list, a full-screen `TYPE_APPLICATION_OVERLAY`
  window covers it.
- The overlay offers exactly two exits: go home, or open the settings app.

Because the overlay is a `WindowManager` view rather than an Activity, it does
not appear in the recents stack and cannot be dismissed with a back gesture to
the app underneath. The blocked app keeps running; it just cannot be seen or
touched.

The whole thing is nine Kotlin files, one `SharedPreferences` store, and two
AndroidX dependencies. There is no server to trust and no `INTERNET` permission
to abuse.

---

## Features

| | Feature | Details |
| :-- | :-- | :-- |
| 📱 | **App block list** | Searchable, multi-select picker over every launchable app. Selection is stored by package name. |
| 🛡 | **Full-screen block screen** | Shows the blocked app's icon and a reason, with *Go home* and *Open App Blocker*. Non-dismissible by the app underneath. |
| ⏱ | **Focus session** | One tap starts a 25-minute window that enforces the block list. The end time is a timestamp, so no service has to stay awake. |
| 🗓 | **Recurring schedules** | Named windows with per-day toggles. Cross-midnight windows (`21:00 → 04:00`) are handled correctly. |
| 🌐 | **Domain block list** | Add hosts like `x.com`; when a supported browser shows a matching URL bar, the tab is sent back and the browser goes home. Subdomains match. |
| 🎞 | **Instagram Reels filter** | Detects the Reels tab or player and bounces to the home feed. Stories are explicitly left alone. |
| ▶️ | **YouTube Shorts filter** | Detects the Shorts tab or player and returns to the Home tab. |
| 🔁 | **Optional watchdog** | A foreground service that polls `UsageStatsManager` once a second, for devices that drop accessibility events. Off by default. |
| 🔒 | **No network code** | The manifest has no `INTERNET` permission. Nothing can be uploaded even by accident. |
| 💾 | **No database** | All state is JSON in one `SharedPreferences` file. Uninstalling leaves nothing behind. |

### Block reasons

The overlay does not just say "blocked" — it tells you *why*, and the wording
changes per source:

| Reason | Shown when | Overlay title |
| :-- | :-- | :-- |
| `Always` | The app is on the block list and nothing else is active | "*App* is blocked" |
| `Focus` | A focus session is running | "*App* is paused for focus" |
| `Scheduled` | A schedule window is active | "*App* is locked" + schedule name |

---

## What is enforced, exactly

`BlockerConfig.reasonToBlock()` is the single decision point. It is called on
every foreground change, and it re-reads preferences every time:

```text
reasonToBlock(package):
  ├─ is it this app?                  → never block
  ├─ is it the launcher / system UI?  → never block
  ├─ is it in the block list?         → if not, allow
  │
  ├─ an enabled schedule active now?  → Scheduled(label)
  ├─ a focus session still running?   → Focus
  └─ otherwise                        → Always
```

Two consequences worth knowing:

1. **Schedules outrank focus sessions.** A schedule is a commitment; a focus
   session is an intention.
2. **The block list is always the gate.** Schedules and focus sessions do not
   add new apps — they decide *when* the existing list applies.

The overlay is also given an expiry, computed from the active schedule or focus
end time, so it disappears on its own at 04:00 instead of lingering until the
next window change.

### Domain blocking

Only these browsers are inspected, each via its own URL-bar view ID:

| Browser | Package |
| :-- | :-- |
| Chrome | `com.android.chrome` |
| Brave | `com.brave.browser` |
| Edge | `com.microsoft.emmx` |
| Samsung Internet | `com.sec.android.app.sbrowser` |
| Opera / Mini / GX | `com.opera.*` |
| Firefox | `org.mozilla.firefox` |
| Firefox Focus | `org.mozilla.focus` |
| DuckDuckGo | `com.duckduckgo.mobile.android` |
| Kiwi | `com.kiwibrowser.browser` |
| Vivaldi | `com.vivaldi.browser` |
| Mi Browser | `com.mi.globalbrowser` |
| UC Browser | `com.UCMobile.intl` |

Host matching normalises the URL (strips scheme, `www.`, path, port) and matches
either the exact host or a subdomain. If the URL field is focused — you are
typing — it does nothing, so searching for a blocked term is not punished.

### Reels and Shorts

Both filters are best-effort by nature: they read view IDs that Instagram and
YouTube ship today.

- **Reels:** ignores Stories (a progress bar is present), detects the player
  container or selected Reels tab, clicks the home/feed tab when it can, and
  falls back to a Back gesture.
- **Shorts:** same pattern with the Shorts player containers and tab.

A short cooldown (2 s) prevents the service from fighting the UI in a loop, and
a toast tells you what happened.

---

## How it works

```text
┌─────────────────────────────────────────────────────────────────────┐
│  AccessibilityService.onAccessibilityEvent                          │
│    TYPE_WINDOW_STATE_CHANGED                                        │
└───────────────────────────────┬─────────────────────────────────────┘
                                │ package changed
                                ▼
                    ┌───────────────────────┐
                    │  BlockerConfig        │  reads SharedPreferences
                    │  reasonToBlock()      │  on every call
                    └───────────┬───────────┘
                                │ BlockReason?
              ┌─────────────────┴──────────────────┐
              │ null                               │ Always / Focus / Scheduled
              ▼                                    ▼
        allow the app                  ┌───────────────────────────┐
                                       │  BlockOverlay.show()      │
                                       │  TYPE_APPLICATION_OVERLAY │
                                       │  FLAG_SECURE, opaque      │
                                       └───────────────────────────┘

  content events ──► browser URL bar ──► host match ──► BACK + HOME
  content events ──► Reels / Shorts view IDs ──► tab click or BACK
```

### Design decisions

**Nothing is cached in memory.** `BlockerConfig` reads preferences on every
event. The accessibility service is restarted by the system unpredictably, and
stale companion-object state is the usual cause of "I turned it off and it still
blocks".

**The overlay is not an Activity.** An Activity lands in the recents stack, can
be finished by the app underneath, and shows the navigation bar. A
`WindowManager` view with `FLAG_LAYOUT_IN_SCREEN` stays put.

**The overlay cannot see the app.** `FLAG_SECURE` keeps the block screen out of
screenshots and screen recordings. It contains no content from the blocked app
because it never reads any.

**One decision point.** The accessibility service and the watchdog both call
`reasonToBlock()`. There is no second implementation to drift.

---

## Project layout

```text
android-app-blocker/
├── app/src/main/
│   ├── AndroidManifest.xml                  permissions, services, activities
│   ├── java/dev/ninjalabs/appblocker/
│   │   ├── MainActivity.kt                  permission cards, focus, options
│   │   ├── AppPickerActivity.kt             searchable multi-select app list
│   │   ├── ScheduleActivity.kt              create / edit / delete windows
│   │   ├── BlockerAccessibilityService.kt    enforcement: apps, sites, Reels, Shorts
│   │   ├── BlockOverlay.kt                   full-screen block screen
│   │   ├── BlockMonitorService.kt            optional UsageStats watchdog
│   │   ├── BlockerConfig.kt                  prefs store + reasonToBlock()
│   │   ├── Schedule.kt                       schedule model + cross-midnight logic
│   │   └── UsageStats.kt                     foreground package + permission check
│   └── res/
│       ├── layout/                           main, picker, schedule, block rows
│       ├── drawable/                         buttons, cards, input, launcher
│       ├── values/                           strings, colors, theme
│       └── xml/                              accessibility_service_config
├── .github/workflows/android.yml            CI: assembleDebug on every push
├── gradle/wrapper/                          Gradle 9.1.0
└── build.gradle.kts                         AGP 8.13.2, Kotlin 2.2.21
```

If you read one file, read `BlockerAccessibilityService.kt` followed by
`BlockerConfig.kt`. Everything else is configuration and UI.

---

## Quick start

### Requirements

| Tool | Version |
| :-- | :-- |
| JDK | 17 or newer |
| Android SDK | API 36 platform |
| Device | Android 8.0 (API 26) or newer |

### Build

```bash
git clone https://github.com/AdilSiddiquiHQ/android-app-blocker.git
cd android-app-blocker

./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk   (~3.4 MB, debug)
```

Release build with R8 shrinking:

```bash
./gradlew :app:assembleRelease
```

### Install and enable

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then, in the app:

1. **Accessibility service** — tap *Enable* → Settings → Accessibility →
   *App Blocker* → On. *(required)*
2. **Display over other apps** — tap *Enable* → allow. *(required)*
3. **Usage access** — only if you want the watchdog. *(optional)*

Choose apps → pick from the list. The block list is active immediately; no
restart, no service toggle.

---

## Permissions

| Permission | Why | If denied |
| :-- | :-- | :-- |
| `BIND_ACCESSIBILITY_SERVICE` | See which package reaches the foreground. This is the enforcement trigger. | Nothing is blocked. |
| `SYSTEM_ALERT_WINDOW` | Draw the block screen over the blocked app. | The service detects the app but has nothing to show. |
| `PACKAGE_USAGE_STATS` | Let the optional watchdog poll the foreground app. | Watchdog switch stays off. |
| `FOREGROUND_SERVICE` + `..._DATA_SYNC` | Run the watchdog with a visible notification. | Watchdog cannot start (API 26+). |
| `POST_NOTIFICATIONS` | Show that notification on Android 13+. | Watchdog runs without a visible notification. |
| `QUERY_ALL_PACKAGES` | List launchable apps in the picker. | The picker would show almost nothing. |

`QUERY_ALL_PACKAGES` and `AccessibilityService` are both restricted on Google
Play. See [distribution](#what-it-cannot-do).

The app deliberately does **not** request `INTERNET`, `READ_EXTERNAL_STORAGE`,
`WRITE_EXTERNAL_STORAGE`, `CAMERA`, `LOCATION`, or `READ_CONTACTS`.

---

## What it cannot do

Blocking on Android without root is cooperative, not a security boundary. Being
straight about that is more useful than pretending otherwise:

- **The blocked app still runs.** It is covered, not killed. Killing it needs
  root or a device-owner profile. Music keeps playing.
- **Permissions are the off switch.** Revoke overlay or accessibility access and
  blocking stops. That is intentional — an app that cannot be unblocked is
  malware.
- **Accessibility can be evaded.** Apps that draw with `FLAG_SECURE`, use
  custom rendering, or hide their window from accessibility may not be
  detectable. System dialogs and the notification shade are never blocked.
- **Reels and Shorts break on app updates.** Instagram and YouTube rename view
  IDs between releases. Expect to update the ID lists a few times a year.
- **Site blocking is not a firewall.** It reads the visible URL bar; it does not
  intercept traffic. Incognito, VPN browsers, in-app browsers, and unsupported
  browsers are not covered.
- **The watchdog is a fallback, not a fix.** On Android 14+ the `dataSync`
  foreground service type has a daily runtime cap; on aggressive OEM builds
  (MIUI, One UI, ColorOS) background services get killed anyway. Battery
  optimisation exemptions help.
- **No device-owner mode.** This is a normal user app, so it cannot block
  Settings, the installer, or safely boot itself.
- **Distribution.** Google Play restricts accessibility APIs to approved
  use cases. This is meant for sideloading, F-Droid, or internal/enterprise
  deployment unless you complete Play's accessibility declaration and review.

If you need a hard guarantee, use a device-owner (MDM) deployment on a managed
profile. That is a different product.

---

## Privacy

The privacy model here is structural rather than a promise:

| Claim | How you can verify it |
| :-- | :-- |
| No data leaves the device | `AndroidManifest.xml` has no `INTERNET` permission. A process without it cannot open a socket. |
| No accounts, no telemetry | There is no networking library, no analytics SDK, and no login screen. |
| No document or screen capture | The service reads the foreground package name and, for browsers, the URL bar text. It stores neither. |
| No hidden state | Everything lives in `SharedPreferences("app_blocker_prefs")`. Uninstall clears it. |
| No background uploads | `allowBackup="false"`, so the config is not even copied to Google's backup service. |

You can confirm the permission list yourself:

```bash
aapt dump permissions app/build/outputs/apk/debug/app-debug.apk
```

---

## Dependencies

Two runtime dependencies, both AndroidX:

```kotlin
implementation("androidx.core:core-ktx:1.13.1")
implementation("androidx.appcompat:appcompat:1.7.1")
```

No Compose, no Hilt, no Retrofit, no Room, no Firebase, no Flutter. The UI is
plain `Activity` + XML layouts, so the whole thing stays readable and the debug
APK stays small.

---

## Development

```bash
./gradlew :app:assembleDebug     # build
./gradlew :app:lintDebug         # Android lint
./gradlew :app:testDebugUnitTest # unit tests (none yet)
```

CI runs `assembleDebug` on every push and pull request via
`.github/workflows/android.yml`.

### Conventions

- Kotlin official style, 4-space indent, trailing commas.
- No in-memory caching of configuration; read `BlockerConfig` at the decision
  point.
- Anything that touches accessibility must degrade to "do nothing" rather than
  crash. Every node lookup is wrapped and every `AccessibilityNodeInfo` is
  recycled.
- New strings go in `res/values/strings.xml`; no hardcoded user-facing text.

### Adding a browser

Add one entry to `BROWSER_URL_BARS` in `BlockerAccessibilityService.kt`:

```kotlin
"com.example.browser" to "com.example.browser:id/url_bar",
```

The generic fallback also looks for node IDs containing `url`, `location_bar`,
`omnibox`, or `address`.

---

## FAQ

<details>
<summary><b>Why not just use Digital Wellbeing?</b></summary>

Digital Wellbeing is a dashboard, not an enforcement layer. Its app timer can be
extended in two taps and it does not block websites or short-form feeds.
</details>

<details>
<summary><b>Why does it need accessibility, which sounds scary?</b></summary>

Accessibility is the only unrooted way to learn which app is in the foreground
and to read a browser's URL bar. This service does not read screen text, does
not store content, and does not transmit anything. It can be revoked at any
time in Settings.
</details>

<details>
<summary><b>Will it survive a reboot?</b></summary>

If the accessibility service was enabled, Android restarts it. The watchdog
foreground service does not auto-start unless you add a boot receiver. Config
lives in preferences, so the block list is intact either way.
</details>

<details>
<summary><b>Can I block Settings or the Play Store?</b></summary>

Technically yes, but don't. Blocking the app that lets you unblock everything is
a good way to brick your own workflow. System UI and the launcher are always
excluded by design.
</details>

<details>
<summary><b>Does it work on a work profile?</b></summary>

Partially. An accessibility service in the primary user cannot see apps inside a
separate work profile. A device-owner deployment would be the correct tool.
</details>

<details>
<summary><b>How large is the app?</b></summary>

~1,600 lines of Kotlin across 9 files, 17 resource XML files, and a 3.4 MB debug
APK. Release builds are smaller because R8 and resource shrinking are on.
</details>

---

## Contributing

Issues and PRs are welcome, particularly:

- URL-bar view IDs for browsers not in the table
- Reels / Shorts view IDs after an app update
- Reports of OEMs that kill the service despite the watchdog

Please keep changes small and in the existing style. If you add a permission,
explain in the PR why the feature cannot work without it. Run
`./gradlew :app:assembleDebug` and `./gradlew :app:lintDebug` before opening a
PR.

---

## License

MIT — see [LICENSE](LICENSE).

<div align="center">
<sub>Built because "just use willpower" is not a scheduling API.</sub>
</div>
