# Distribution

Ostrom has no store accounts of its own yet. Until it does (Nathan, 2026-11-13), the app goes through TeamCode's:

- The iOS app is signed with TeamCode's Apple team (`DEVELOPMENT_TEAM` `Z8T3MHFW48`, `OSTROM_DEVELOPMENT_TEAM` in
  `companion-ios/Configuration/Config.xcconfig`), with Ostrom's own bundle identifier `run.ostrom.app`.
- Android uses the package `run.ostrom.app`.
- Uploads go to **TestFlight and Play's internal track only**. No lane submits for review or releases publicly.

## Store builds: TeamCode's fastlane, as is

`Gemfile`, `fastlane/Appfile` and `fastlane/Fastfile` are copies of TeamCode's. Nathan runs the same commands, from
his Mac, at the repository root (README, "Release"):

```sh
bundle exec fastlane ios beta version:X.Y.Z       # TestFlight
bundle exec fastlane android beta version:X.Y.Z   # Play internal track
```

The lanes read their keys from the same local paths as TeamCode. These files are git-ignored, and Nathan puts them there
himself. They are never committed, copied or written by CI:

| File | For |
| --- | --- |
| `fastlane/api_key.json` | App Store Connect API key (`key_id`, `issuer_id`, `key`, in fastlane's format) |
| `fastlane/key.p8` | The same key's `.p8`, for Xcode's cloud signing |
| `fastlane/play-key.json` | Google Play service account |
| `fastlane/keystore.jks` | Android upload key, with `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` in the environment |

Lines changed from TeamCode's files:

- `fastlane/Appfile`: `app_identifier` and `package_name` are `run.ostrom.app`.
- `fastlane/Fastfile`:
  - `XCODEPROJ` is `companion-ios/Ostrom.xcodeproj` and `SCHEME` is `Ostrom`.
  - The version property is `ostromAppVersion`, both as `ORG_GRADLE_PROJECT_ostromAppVersion` for Xcode's Gradle phase
    and in the Android lane. The version code property is `ostromVersionCode`.
  - The messages and comments say "Ostrom" or "the app version" instead of TeamCode.
- `Gemfile`: unchanged. `Gemfile.lock` stays untracked, as in TeamCode.

Before the first upload, once: the App Store Connect app record for `run.ostrom.app` on team `Z8T3MHFW48`, and the
Play Console app `run.ostrom.app` with its first bundle uploaded by hand (Play's API cannot create an app).

**Versions.** The app carries the version given to the lane (`MARKETING_VERSION`, `versionName`, and the shared code's
`BuildInfo.VERSION` through `ostromAppVersion`). The build number (`CFBundleVersion`, `versionCode`) is
`(major × 1,000,000 + minor × 1,000 + patch) × 100 + n`, where `n` counts the uploads of that version (read from
TestFlight and the Play tracks, at most 99). Without the lane, Gradle builds use `n = 0`.

**Android release signing**, as TeamCode's: `companion-android` signs its release build only when
`fastlane/keystore.jks` exists, with the passwords from `SIGNING_*`. Otherwise the release build is unsigned and the
lane refuses to run. CI builds the debug APK only.

## What CI builds

| Platform | What | Where |
| --- | --- | --- |
| Android | Debug APK (debug key) | `ci` job on `main`, artifact `ostrom-android-debug` |
| iOS | Simulator build, `CODE_SIGNING_ALLOWED=NO` | `ios` job on `main` |
| macOS, Linux, Windows | Runnable jar of each system (`packageUberJarForCurrentOS`) | `desktop` jobs on `main`, artifacts `ostrom-desktop-<system>` |

## Desktop, later

The desktop app's bundle identifier is `run.ostrom.app.desktop`, and Windows uses a fixed upgrade code. Installers (DMG,
MSI, DEB, RPM) refuse a 0.x version, so they come with 1.0.0. Signing and notarizing the macOS app will follow
TeamCode's `build.yml`. It will need `OSTROM_MACOS_CERTIFICATE_P12`, `OSTROM_MACOS_CERTIFICATE_PASSWORD`,
`OSTROM_NOTARY_KEY`, `OSTROM_NOTARY_KEY_ID`, `OSTROM_NOTARY_ISSUER_ID` and the variable `OSTROM_MACOS_TEAM_ID`. Windows
installers stay unsigned, as TeamCode's.
