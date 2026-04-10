# Flashcards Everywhere

> Anki, but it reaches you everywhere.

A beautiful Android client for AnkiDroid that pushes your due cards into every
ambient surface of the phone — notifications, lockscreen, and home-screen
widgets.

The goal: convert hours of passive phone usage into hours of passive review.
**AnkiDroid stays the source of truth.** This app never re-implements the
scheduler; it queries the AnkiDroid `FlashCardsContract` ContentProvider, lets
the user grade cards, submits the answers back, and asks AnkiDroid to sync to
AnkiWeb.

---

## Status

Pre-alpha. Project skeleton + foundational modules are scaffolded; the visual
identity (Apple-minimal reviewer) and the AnkiDroid bridge are real and runnable.
The ambient surfaces (notification, widget, lockscreen) are wired into the
manifest with working stubs and need their behaviour fleshed out per the
milestone plan.

| Milestone | Description                                       | Status |
|-----------|---------------------------------------------------|--------|
| M0        | Gradle skeleton, two flavors, theme               | ✅ done |
| M1        | `data-anki` (FlashCardsContract bridge)           | ✅ done |
| M2        | In-app reviewer (Apple-minimal Compose)           | ✅ done — WebView card rendering, theme, ReviewSession |
| M2.5      | Room offline answer queue                         | ✅ done |
| M3        | Notifications + lockscreen surfaces               | ✅ done — orchestrator, FSI fallback for Android 14+, GradeReceiver |
| M4        | Glance home-screen widget                         | ✅ done — wired to ReviewSession via Hilt EntryPoint, grade buttons fire `GradeReceiver` |
| M5        | Pacing engine + onboarding                        | ✅ done — `PacingEngine`, `UsagePulseSource`, `PacingService` ticker, onboarding walkthrough |
| M6        | ~~AccessibilityService overlay~~                  | 🚫 dropped — see [Why no AccessibilityService](#why-no-accessibilityservice) |
| M7        | Enforcement modes (full flavor)                   | ⚪ not started — app block + lockout mode |

---

## Build

Three options, fully documented in [`docs/BUILD.md`](docs/BUILD.md):

1. **GitHub Actions** (recommended for CI / AI agents) — push a `v*` tag and
   `.github/workflows/build-apk.yml` produces both flavor APKs as permanent
   GitHub Release downloads. Free, no Expo account, no local SDK.
2. **Local with the Gradle wrapper** — JDK 17 + Android SDK 35:
   ```bash
   chmod +x ./gradlew
   ./gradlew assembleLiteDebug   # Play Store flavor
   ./gradlew assembleFullDebug   # F-Droid flavor
   ```
3. **Android Studio** — open the project root, click Run.

### Two product flavors

| Flavor  | Distribution            | Includes                                   |
|---------|-------------------------|--------------------------------------------|
| `lite`  | Play Store              | Notifications, widget, lockscreen, UsageStats pacing |
| `full`  | F-Droid + GitHub Releases | Everything in `lite`, plus the upcoming M7 enforcement modes (app blocks, lockout) once those land |

The split is reserved for M7 enforcement features that will need extra
permissions Google does not allow on Play Store builds. Today the two flavor
APKs are nearly identical; install whichever your distribution channel ships.

## Install

> **If you sideload the APK by tapping it in a browser or messenger, recent
> Android builds will show "App blocked to protect your device. This app can
> request to access to sensitive data."** That dialog is Google Play Protect's
> Enhanced Fraud Protection. It only fires for the legacy non-session install
> path. Use any of the routes below instead — they all use the modern
> `PackageInstaller` Session API and bypass the warning.

In rough order of recommendation:

1. **[Obtainium](https://github.com/ImranR98/Obtainium)** *(recommended)* —
   point it at this repo's GitHub Releases and it will install + auto-update
   straight from there. Best UX for non-Play sideloaders.
2. **F-Droid client** — once we publish to F-Droid, install through the F-Droid
   app, not by downloading the APK from the F-Droid website and tapping it.
3. **`adb install` from a computer** — for developers and CI:
   ```bash
   adb install -r -t app/build/outputs/apk/fullDebug/app-full-debug.apk
   ```
   `-t` allows test/debug APKs; `-r` reinstalls over an existing copy.
4. **[Split APKs Installer (SAI)](https://github.com/Aefyr/SAI)** or
   **[InstallWithOptions](https://github.com/zacharee/InstallWithOptions)** —
   third-party session installers if you want a tap-to-install flow without
   Obtainium.

### Why no AccessibilityService

The full flavor used to ship a `CardOverlayAccessibilityService` that detected
foreground app changes via `TYPE_WINDOW_STATE_CHANGED` and drew a card on top
using `TYPE_ACCESSIBILITY_OVERLAY`. We dropped it for two converging reasons:

- **Google Play Protect's Enhanced Fraud Protection** auto-blocks any sideload
  whose manifest declares `BIND_ACCESSIBILITY_SERVICE`,
  `BIND_NOTIFICATION_LISTENER_SERVICE`, `READ_SMS`, or `RECEIVE_SMS`. The
  protection has been rolled out to ~185 markets and 2.8B devices through
  2025; users could not install the full flavor at all.
- **Google's Jan 2026 AccessibilityService policy** bans non-assistive uses of
  the API on Play Store, and Android 13+'s Restricted Settings already
  prevented users from enabling such a service post-install without an extra
  manual unlock dance.

The "cards on every app launch" surface is gone. The other surfaces
(notification, lockscreen, widget, in-app reviewer) cover the same use case
with permissions that no fraud filter touches.

### Running against AnkiDroid

You need AnkiDroid (release or debug build) installed on the same device or
emulator. On first launch the app asks for the `READ_WRITE_DATABASE` dangerous
permission (a custom AnkiDroid permission shown as a system-style dialog).

If the dialog never appears, check that the `<queries>` block in
`AndroidManifest.xml` includes `com.ichi2.anki` — without it, package
visibility hides AnkiDroid entirely on Android 11+.

---

## Project layout

```
FlashcardsEverywhere/
├── app/
│   ├── src/main/             # All code today (data layer, UI, services)
│   ├── src/full/             # Reserved for M7 enforcement features
│   ├── src/lite/             # Reserved for Play-Store-safe overrides
│   └── build.gradle.kts
├── docs/
│   ├── ANKI_CONTRACT_REFERENCE.md     # Ground-truth FlashCardsContract spec
│   ├── ANKIDROID_WEAR_NOTES.md        # Patterns from the only existing reviewer client
│   └── ANDROID_SURFACES_REFERENCE.md  # Glance / FullScreenIntent / a11y / UsageStats reference
├── gradle/libs.versions.toml
├── settings.gradle.kts · build.gradle.kts · gradle.properties
├── LICENSE                            # GPLv3
└── README.md
```

### Source-of-truth packages

- `data/anki/` — `FlashCardsContract.kt` (constants), `AnkiBridge.kt` (the
  ContentProvider wrapper), `AnkiModels.kt`, `AnkiPermissionHelper.kt`. **The
  most important module — this is where the contract docs become code.**
- `domain/ReviewSession.kt` — single in-process review session shared by all
  surfaces.
- `ui/reviewer/` — Compose reviewer screen (the visual showpiece).
- `surface/notification/` — `NotificationOrchestrator`, `GradeReceiver`.
- `surface/lockscreen/` — `LockscreenReviewerActivity`.
- `surface/widget/` — Glance `CardWidget` + receiver.
- `service/pacing/` — `PacingService` (foreground service) + `BootReceiver`.

---

## Architecture in one paragraph

`AnkiBridge` exposes coroutine-based reads/writes against AnkiDroid's
`FlashCardsContract` ContentProvider. `ReviewSession` (singleton) holds the
current queue of due cards in memory and exposes a `StateFlow<ReviewState>`
that every surface — in-app reviewer, lockscreen activity, notification,
widget — observes. `PacingService` runs in the foreground, watches
`UsageStatsManager`, and asks `NotificationOrchestrator` to surface cards on
the user's chosen schedule. `GradeReceiver` lets any surface answer a card via
PendingIntent without opening the app. AnkiDroid handles FSRS scheduling and
AnkiWeb sync — we never duplicate either.

---

## License

GPL v3+. See [`LICENSE`](LICENSE).

This mirrors AnkiDroid's licensing model. The reference implementation we
studied (`wlky/AnkiDroid-Wear`) is GPLv2-only and license-incompatible with
GPLv3 — none of its code was copied. The patterns are re-implemented from the
contract specification documented in `docs/ANKI_CONTRACT_REFERENCE.md`.

---

## Acknowledgements

- The [AnkiDroid](https://github.com/ankidroid/Anki-Android) team, for keeping
  the `FlashCardsContract` ContentProvider stable and documented.
- [`wlky/AnkiDroid-Wear`](https://github.com/wlky/AnkiDroid-Wear), the only
  prior third-party reviewer client and the inspiration for this project's
  data layer.
- The FSR team, whose scheduler makes everything above worth doing.
