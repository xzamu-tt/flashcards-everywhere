# How to build Flashcards Everywhere

This is a **native Kotlin/Android** project. It does **not** use Expo, React Native,
Flutter, or any cross-platform framework, so EAS Build does **not** apply
(EAS Build is for Expo / RN projects). The cloud-build path of choice is
**GitHub Actions**, which is configured in `.github/workflows/build-apk.yml`.

There are three ways to build, in order of recommendation for AI agents:

1. **GitHub Actions** — remote, no local Android SDK required, produces a
   permanent download URL via GitHub Releases. **Use this from agents.**
2. **Local with the Gradle wrapper** — fast iteration, requires JDK 17 +
   Android SDK 35.
3. **Android Studio** — for humans clicking buttons.

---

## 1. GitHub Actions (the agent path)

### One-time setup (human, ~5 min)

1. Push the project to a GitHub repo.
2. That's it. The workflow `.github/workflows/build-apk.yml` is already
   committed and runs automatically.

No secrets, no keystores, no Expo account, no payment.

### Triggering a build

Three ways to fire the workflow:

| Trigger              | When             | Output                                          |
|----------------------|------------------|-------------------------------------------------|
| Push to `main`       | Every commit     | APK as 90-day workflow artifact                 |
| Push a `v*` tag      | Manual           | APK as 90-day artifact **and** permanent Release |
| Manual `workflow_dispatch` | Any time   | APK as 90-day artifact                          |

### How an AI agent triggers a release-quality build and gets a download URL

```bash
# 1. Stage and push (agent already has gh CLI auth)
cd /path/to/FlashcardsEverywhere
git add -A
git commit -m "Build $(date +%Y%m%d-%H%M)"
git push origin main

# 2. Tag a release. The workflow will build, attach APKs to the release.
TAG="v0.$(date +%Y%m%d.%H%M)"
git tag -a "$TAG" -m "Build $TAG"
git push origin "$TAG"

# 3. Wait for the workflow to finish (~6-8 min cold, ~3-4 min cached)
gh run watch --exit-status

# 4. Get the permanent download URLs from the release
gh release view "$TAG" --json assets --jq '.assets[].url'
# → https://github.com/<owner>/<repo>/releases/download/v0.20260410.0301/flashcards-everywhere-v0.20260410.0301-lite.apk
# → https://github.com/<owner>/<repo>/releases/download/v0.20260410.0301/flashcards-everywhere-v0.20260410.0301-full.apk
```

The user can paste either URL into their phone's browser and install.

### How an agent gets the APK from a non-tag run (no release)

If you don't want to create a release, you can grab the workflow artifact:

```bash
# Find the most recent successful build-apk run
RUN_ID=$(gh run list --workflow=build-apk.yml --status=success --limit=1 --json databaseId --jq '.[0].databaseId')

# Download all APK artifacts from that run into ./artifacts
gh run download "$RUN_ID" -n flashcards-everywhere-apks -D artifacts

ls artifacts/*.apk
```

Workflow artifacts are valid for 90 days but the URL is **only accessible to
authenticated GitHub users**, so they can't be shared publicly. Use the
release path if the user needs a public link.

### Building only one flavor

```bash
gh workflow run build-apk.yml -f flavor=lite     # Play Store flavor only
gh workflow run build-apk.yml -f flavor=full     # F-Droid flavor only
gh workflow run build-apk.yml -f flavor=both     # Default
```

### Free-tier limits

GitHub Actions free tier on public repos: **unlimited** minutes. On private
repos: 2,000 min/month (each `build-apk` run uses ~7 min, so ~280 builds/mo).

---

## 2. Local with the Gradle wrapper

### Prerequisites

- **JDK 17** (Temurin or Zulu — confirmed working)
- **Android SDK 35** with build-tools 35.0.0
- **`adb`** in PATH if you want to install on a device

```bash
# macOS quick start
brew install --cask temurin@17
brew install --cask android-commandlinetools
sdkmanager "platforms;android-35" "build-tools;35.0.0" "platform-tools"
```

### Build

```bash
cd FlashcardsEverywhere
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
chmod +x ./gradlew

./gradlew assembleLiteDebug      # Play Store flavor
./gradlew assembleFullDebug      # F-Droid flavor
```

APKs land in `app/build/outputs/apk/{lite,full}/debug/app-*-debug.apk`.

### Critical: build directory redirection

The repo's `build.gradle.kts` redirects all build output to
`~/.cache/flashcards-everywhere/` instead of the in-tree `build/` directory.

**Why this matters:** the project lives on an HFS+/exFAT external drive
(`/Volumes/T7`) which auto-creates `._*` AppleDouble resource fork files on
every file write. KSP and AAPT both refuse to process directories containing
these files, so a stock build directory on T7 fails with cryptic
`NullPointerException` (KSP) or `'…/._drawable' is not a directory` (AAPT).

If you move the project off the external drive, you can remove the
`allprojects { layout.buildDirectory.set(...) }` block in `build.gradle.kts`.

### Install on a USB-connected device

```bash
adb devices                                          # confirm device is seen
adb install -r app-lite-debug.apk                    # or app-full-debug.apk
adb logcat -s "FlashcardsApp:*" "AnkiBridge:*" "*:E" # tail logs
```

---

## 3. Android Studio (humans)

1. Open the project root (`FlashcardsEverywhere/`) in Android Studio
   Ladybug or newer.
2. It will auto-detect the Gradle wrapper, sync, and download dependencies.
3. Pick the build variant (`liteDebug` or `fullDebug`) from the Build
   Variants panel.
4. Run ▶ on a connected device or emulator.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `KSP NullPointerException at qualifiedName` | macOS `._*` files in `build/generated/ksp/` | Make sure you're not on HFS+/exFAT, or check that the build directory redirection in `build.gradle.kts` is intact |
| `'…/._drawable' is not a directory` | Same | Same |
| `gradle wrapper` download timeout | Default 10s timeout too short on flaky networks | Already fixed: `gradle-wrapper.properties` sets `networkTimeout=120000` |
| `Failed to find Build Tools revision X` | SDK not synced | `sdkmanager "build-tools;35.0.0"` |
| `SDK location not found` | No `local.properties` | `echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties` |
| `Device unauthorized` (`adb devices` shows `unauthorized`) | USB debugging consent dialog | Unlock the phone, accept the dialog, retry |

---

## Why not EAS Build?

EAS Build is for Expo and React Native projects. It cannot build a pure
Kotlin/Android Gradle project — even if you point it at one, it expects an
`app.json` with Expo plugins and tries to run `npx expo prebuild`, which has
nothing to do with this repo.

For Android-native projects the equivalent of "EAS-but-for-Android" is one of:

- **GitHub Actions** ← what we use, configured here
- **Bitrise** — Android-focused, generous free tier, also produces download URLs
- **Codemagic** — Flutter/Android/iOS, similar model
- **Firebase App Distribution** — distribution-only; pair with one of the above for the build

GitHub Actions wins on zero-config, zero-cost, and zero-extra-account-needed
when the source is already on GitHub.

---

## Agent recipe (copy-paste)

```bash
# === BUILD A NEW APK AND GET A PUBLIC DOWNLOAD URL ===
# Prereqs: gh CLI authenticated, repo pushed to GitHub, on the project root.

cd /path/to/FlashcardsEverywhere

# 1. Push any pending changes
git add -A && git commit -m "agent build" || true
git push origin main

# 2. Cut a tag — this triggers the release workflow
TAG="v0.$(date +%Y%m%d.%H%M)"
git tag -a "$TAG" -m "Agent build $TAG"
git push origin "$TAG"

# 3. Wait for the workflow to finish (auto-exits non-zero on failure)
gh run watch --exit-status

# 4. Print the permanent download URLs (one per flavor)
gh release view "$TAG" --json assets \
  --jq '.assets[] | {name: .name, url: .browserDownloadUrl}'
```

The two URLs printed in step 4 are what you give the user. They are
permanent, public, and require no login.

---

## Notes for AI agents

- **Never** build from `/Volumes/T7/...` without the build-directory redirect
  — KSP will fail on AppleDouble files.
- **Never** commit `local.properties` (it's in `.gitignore`).
- **Never** commit a signing keystore — use GitHub Secrets if you ever need
  to ship a release-signed build.
- The first cold build on GitHub Actions is ~6-8 min; subsequent cached
  builds are ~3-4 min. Don't trigger redundant builds.
- If `gh run watch` reports failure, run `gh run view --log-failed` to get
  the exact error.
- Both flavors are built by default. If you only need one, pass
  `-f flavor=lite` or `-f flavor=full` to `gh workflow run build-apk.yml`.
- Debug APKs are unsigned and ~62 MB. Release-signed APKs would need a
  keystore + a `signingConfigs` block in `app/build.gradle.kts` + the
  keystore as a GitHub Secret. Out of scope for now.
