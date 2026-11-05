# App Blocker

A small, self-contained Android app blocker built on `AccessibilityService`.

Most open-source blockers in this niche are Flutter plugins that wrap a broken
native layer, or apps that need root, a VPN, or a device-owner profile. This one
is plain Kotlin, has no backend, no analytics, and no network permission at all.
It covers a blocked app with a full-screen overlay and gets out of the way.

This is the blocker layer from a focus app I ship, rewritten as a standalone
sample so the idea is readable on its own.

## What it does

- **Block a list of apps.** Pick apps from a searchable list; they are covered by
  the block screen whenever they come to the foreground.
- **Focus sessions.** Start a 25-minute session and the block list is enforced
  until it ends. No service needed: the end time is a timestamp.
- **Schedules.** Recurring windows (for example 21:00–04:00, Monday–Friday) that
  enforce the same block list. Cross-midnight windows are handled.
- **Website blocklist.** Add domains; when a supported browser's URL bar shows a
  matching host, the browser is sent home.
- **Instagram Reels and YouTube Shorts.** Best-effort filtering using the view
  IDs those apps expose to accessibility.
- **Optional watchdog.** A foreground service that polls the foreground app once
  a second for devices that drop accessibility events.

## How it works

```text
AccessibilityService.onAccessibilityEvent(TYPE_WINDOW_STATE_CHANGED)
        │
        ├─ ignore our own package, the launcher, system UI
        │
        ├─ BlockerConfig.reasonToBlock(package)
        │     ├─ schedule active?     → Scheduled
        │     ├─ focus session live?  → Focus
        │     └─ in block list?       → Always
        │
        └─ BlockOverlay.show(...)   ← full-screen TYPE_APPLICATION_OVERLAY

Browser content events ──► read URL bar node ──► host match ──► BACK + HOME
Reels / Shorts events  ──► inspect view IDs   ──► click Home tab / BACK
```

The important design choice is that **nothing is cached in memory**. The
accessibility service can be restarted by the system at any time, and stale
companion-object state is the usual cause of "I turned it off and it still
blocks". Every event reads current state from `SharedPreferences`.

`BlockOverlay` is a plain `WindowManager` view rather than an Activity. An
Activity would show up in the recents stack and can be dismissed by the app
underneath; an overlay stays on top of whatever is drawn.

## What it does not do

Blocking on Android without root is cooperative, not a security boundary:

- The blocked app still runs in the background. The overlay only covers it.
- If the user revokes overlay or accessibility permission, blocking stops.
- Apps that draw with `FLAG_SECURE` or hide their window from accessibility can
  sometimes evade detection.
- Instagram and YouTube change their view IDs between releases. Reels/Shorts
  detection is best-effort and will need maintenance.
- Website blocking depends on the browser exposing its URL bar to accessibility.
  It does not intercept network traffic.
- Aggressive OEM battery managers may kill the accessibility service. The
  watchdog helps but is not a guarantee.
- Google Play restricts `AccessibilityService` usage. This sample is intended for
  sideloading, F-Droid, or internal distribution unless you go through Play's
  accessibility declaration and review.

The app requests `QUERY_ALL_PACKAGES` because the picker lists launchable apps.
It has no `INTERNET` permission.

## Build

Requirements: JDK 17 and Android SDK 36.

```bash
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Install and grant the two required permissions from the app's permission card:

1. **Accessibility service** — Settings → Accessibility → App Blocker.
2. **Display over other apps** — Settings → Apps → App Blocker → Display over
   other apps.

Usage access is only needed for the optional watchdog.

## Project layout

```text
app/src/main/java/dev/ninjalabs/appblocker/
├── MainActivity.kt                 permissions, focus session, options
├── AppPickerActivity.kt            searchable multi-select app list
├── ScheduleActivity.kt             recurring block windows
├── BlockerAccessibilityService.kt  enforcement + browser / Reels / Shorts
├── BlockOverlay.kt                 full-screen block screen
├── BlockMonitorService.kt          optional UsageStats watchdog
├── BlockerConfig.kt                SharedPreferences config + decisions
├── Schedule.kt                     schedule model and active-window logic
└── UsageStats.kt                   foreground app + usage permission
```

`BlockerAccessibilityService` is the only file that needs to be understood to
grasp the approach. The rest is configuration and UI.

## Data

Everything stays on the device in one `SharedPreferences` file. There is no
account, no telemetry, and no network code. The accessibility service reads the
foreground package name and, for supported browsers, the visible URL bar text;
it does not read other screen content and does not store it.

## License

MIT — see [LICENSE](LICENSE).
