# Security policy

## Scope

App Blocker is an on-device utility. It has no server, no account system, and no
`INTERNET` permission, so the realistic attack surface is local: a malicious app
on the same device, a modified APK, or a permission the user was talked into
granting.

## Reporting

Open a private security advisory on this repository
(**Security → Advisories → Report a vulnerability**) rather than a public issue.
If advisories are unavailable, email the maintainer listed on the GitHub profile.

Please include:

- Android version and device model
- The app version (`versionName` from `app/build.gradle.kts`)
- Steps to reproduce, and what you expected instead
- Whether the device is rooted or has a modified ROM

Expect an acknowledgement within a few days. This is a side project, not a
funded product, so there is no bounty — but credit is given in the release notes
unless you ask otherwise.

## What is not a vulnerability

- The blocked app continues running in the background. The overlay covers it; it
  is not designed to kill processes. Killing them requires root or device-owner
  privileges.
- Blocking stops when the user revokes Accessibility or overlay permission. That
  is intentional; an unblockable blocker is malware.
- Reels or Shorts detection failing after an Instagram or YouTube update. That is
  expected maintenance, not a security bug. Send the new view IDs instead.
- Website blocking not covering a browser that does not expose its URL bar to
  accessibility. This is documented in the README.

## What is a vulnerability

- The service reading or storing content beyond the foreground package name and
  the browser URL bar text.
- Any network egress. The manifest has no `INTERNET` permission; a build that
  adds one is a regression.
- An overlay that crashes, traps the user with no exit, or cannot be dismissed
  after permissions are revoked.
- Configuration data being readable by other apps (the preferences file should
  remain private to the app).
- A component exported without need. `MainActivity` is exported by necessity;
  everything else is not.
