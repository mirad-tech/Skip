# AGENT.md

## Project Summary

Skip is a local-only Android AccessibilityService helper for low-risk splash-page skip/close controls. It is not an ad cracking, ad blocking, app bypass, reverse engineering, or code-copy project.

Current app version is defined in `app/build.gradle.kts`. Keep README and release docs aligned with that value when changing versions.

## Source Of Truth

- App module: `:app`
- Main entry and navigation: `app/src/main/java/com/example/skip/MainActivity.kt`
- Accessibility runtime: `app/src/main/java/com/example/skip/service/SkipAccessibilityService.kt`
- Matching/click/safety engine: `app/src/main/java/com/example/skip/engine/`
- Persistence/import/export/logs: `app/src/main/java/com/example/skip/data/`
- Models: `app/src/main/java/com/example/skip/model/`
- Compose screens: `app/src/main/java/com/example/skip/ui/`
- Accessibility config: `app/src/main/res/xml/accessibility_service_config.xml`
- Rule format: `RULES_GUIDE.md` and `sample_rules.json`
- Diagnostics format: `LOG_DIAGNOSTIC_GUIDE.md`
- Release procedure: `RELEASE_GUIDE.md`

Do not rely on stale local planning files. Check current source and current docs first.

## Safety And Privacy Rules

- Do not add `INTERNET`, storage, contacts, SMS, location, camera, microphone, phone, account, or other unrelated sensitive permissions.
- Keep processing local by default. Do not upload screen contents, rules, logs, stats, or personal data.
- Do not auto-click payment, authorization, login, registration, privacy consent, install, delete, transfer, send, submit, or similar high-risk controls.
- Protected packages and sensitive surfaces include system UI, launchers, installers, permission pages, input methods, payment, banking, wallet, finance, and password-manager apps.
- Coordinate fallback must stay opt-in, app-specific, anchored, cooldown-limited, time-window-limited, and safety-checked.
- Skip must never import or run auto-click rules for its own package.

## Build And Verification

Use the Gradle wrapper from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
git diff --check
.\gradlew.bat :app:assembleRelease
```

Release builds need local signing files:

- `keystore.properties`
- `release.keystore`

These files are intentionally ignored. Never print, commit, or push real signing credentials.

Before release, also check that manifests do not add unrelated permissions:

```powershell
Select-String -Path app\src\main\AndroidManifest.xml -Pattern "uses-permission"
```

## Workspace Hygiene

The following paths are local cache/build/output material and can be regenerated:

- `.gradle/`
- `.gradle-home/`
- `.kotlin/`
- `.android-home/`
- `.kotlin-home/`
- `build/`
- `app/build/`
- `downloads/`

Exception: a specific `downloads/Skip-vX.Y.Z-release.apk` may be force-added only when the user explicitly asks to publish a release APK.

Android Studio local settings and signing files should normally stay local and ignored:

- `.idea/`
- `local.properties`
- `keystore.properties`
- `release.keystore`

The old `LITIAOTIAO_PLAN.md` was a historical implementation plan. Current source, README, and release docs are the authoritative project state.

## Git Process Caution

Do not create background Git loops, watchers, scheduled jobs, or recurring automations for this repository. Use one-shot Git commands only. If many `git.exe status --porcelain`, `git.exe remote -v`, or `git.exe rev-parse HEAD` processes appear, treat them as external polling to diagnose or stop explicitly rather than adding another loop.
