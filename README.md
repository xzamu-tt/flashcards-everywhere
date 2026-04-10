# Flashcards Everywhere

> Anki, but it reaches you everywhere.

A beautiful Android client for AnkiDroid that pushes your due cards into every
ambient surface of the phone — notifications, lockscreen, home-screen widgets,
and (full flavor only) an overlay on top of every app you open.

The goal: convert hours of passive phone usage into hours of passive review.
**AnkiDroid stays the source of truth.** This app never re-implements the
scheduler; it queries the AnkiDroid `FlashCardsContract` ContentProvider, lets
the user grade cards, submits the answers back, and asks AnkiDroid to sync to
AnkiWeb.

---

## Status

Pre-alpha. Project skeleton + foundational modules are scaffolded; the visual
identity (Apple-minimal reviewer) and the AnkiDroid bridge are real and runnable.
The ambient surfaces (notification, widget, lockscreen, accessibility overlay)
are wired into the manifest with working stubs and need their behaviour fleshed
out per the milestone plan.

| Milestone | Description                                       | Status |
|-----------|---------------------------------------------------|--------|
| M0        | Gradle skeleton, two flavors, theme               | ✅ done |
| M1        | `data-anki` (FlashCardsContract bridge)           | ✅ done |
| M2        | In-app reviewer (Apple-minimal Compose)           | ✅ done — WebView card rendering, theme, ReviewSession |
| M2.5      | Room offline answer queue                         | ✅ done |
| M3        | Notifications + lockscreen surfaces               | ✅ done — orchestrator, FSI fallback for Android 14+, GradeReceiver |
| M4        | Glance home-screen widget                         | ✅ done — wired to ReviewSession via Hilt EntryPoint, grade buttons fire `GradeReceiver` |
| M5        | Pacing engine + onboarding                        | ✅ done — `PacingEngine`, `UsagePulseSource`, `PacingService` ticker, onboarding walkthrough |
| M6        | AccessibilityService overlay (full flavor)        | ✅ done — Compose-in-Service, `TYPE_ACCESSIBILITY_OVERLAY`, per-app cooldown |
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
| `full`  | F-Droid + GitHub Releases | Everything in `lite` PLUS AccessibilityService overlay, app blocks, lockout mode |

The split is mandatory: Google's Jan 2026 AccessibilityService policy bans
non-assistive use of the accessibility API, so the "card on every app launch"
feature cannot ship on the Play Store.

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
│   ├── src/main/             # Common code: data layer, UI, services
│   ├── src/full/             # Full-flavor only: AccessibilityService overlay
│   ├── src/lite/             # Lite-flavor only: stubs that match full's package shape
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
- `surface/overlay/` — `CardOverlayAccessibilityService` (full flavor only).
- `service/pacing/` — `PacingService` (foreground service) + `BootReceiver`.

---

## Architecture in one paragraph

`AnkiBridge` exposes coroutine-based reads/writes against AnkiDroid's
`FlashCardsContract` ContentProvider. `ReviewSession` (singleton) holds the
current queue of due cards in memory and exposes a `StateFlow<ReviewState>`
that every surface — in-app reviewer, lockscreen activity, notification,
widget, accessibility overlay — observes. `PacingService` runs in the
foreground, watches `UsageStatsManager` (lite) or accessibility events (full),
and asks `NotificationOrchestrator` to surface cards on the user's chosen
schedule. `GradeReceiver` lets any surface answer a card via PendingIntent
without opening the app. AnkiDroid handles FSRS scheduling and AnkiWeb sync —
we never duplicate either.

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
