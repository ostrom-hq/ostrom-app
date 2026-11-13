# Distribution

Ostrom has no App Store, Google Play or Apple Developer account of its own yet (2026-11-13: Nathan settles with Lyes
which account the app goes on). Until then (Nathan, 2026-11-13):

- **iOS** is signed with **TeamCode's Apple team** (`Z8T3MHFW48`) under Ostrom's own bundle identifier
  `run.ostrom.app`, and uploaded to **TestFlight for internal testing only**. No lane submits for review, adds
  external testers or releases on the App Store.
- **Android** stays debug-signed: no upload key, no Play upload.
- **Desktop** builds are unsigned runnable jars.

| Platform | What is built | Where |
| --- | --- | --- |
| iOS | Signed release build, uploaded to TestFlight (internal testing) | by hand: `bundle exec fastlane ios beta version:X.Y.Z`, or the **TestFlight** workflow (`workflow_dispatch`) |
| iOS | Simulator build, `CODE_SIGNING_ALLOWED=NO` | `ios` job of CI on `main` |
| Android | Debug APK (debug key) | `ci` job on `main`, artifact `ostrom-android-debug` |
| macOS, Linux, Windows | Runnable jar of each system (`packageUberJarForCurrentOS`) | `desktop` jobs on `main`, artifacts `ostrom-desktop-<system>` |

## iOS: TestFlight

Ported from TeamCode's setup (`fastlane/Appfile`, `fastlane/Fastfile`, `Gemfile`): automatic signing with an App
Store Connect API key ("cloud signing"), so no certificate or provisioning profile is kept in a keychain or in this
repository. `companion-ios/Configuration/Config.xcconfig` sets `OSTROM_DEVELOPMENT_TEAM=Z8T3MHFW48`.

The build number is `(major * 1 000 000 + minor * 1 000 + patch) * 100` plus a counter read from TestFlight (up to 99
uploads per version), the same formula as Android's `versionCode`.

Before the first upload, once:

1. In App Store Connect (team `Z8T3MHFW48`), create the app record for the bundle identifier `run.ostrom.app` (name
   "Ostrom"). Xcode registers the identifier and its capabilities on the first signed build.
2. Create the `testflight` environment of `ostrom-hq/ostrom-app`, limit its deployment branches to `main` (so a branch
   cannot run a modified workflow or Fastfile with the key), optionally with a required reviewer, and add its secrets:

| Secret | Value |
| --- | --- |
| `APP_STORE_CONNECT_API_KEY_ID` | The key's ID |
| `APP_STORE_CONNECT_ISSUER_ID` | The issuer ID of the team |
| `APP_STORE_CONNECT_API_KEY` | The `.p8` file's content (the key needs the Admin role for cloud-managed signing) |

From a Mac instead: put the same key in `fastlane/key.p8` and `fastlane/api_key.json` (fastlane's format: `key_id`,
`issuer_id`, `key`), both git-ignored, then run `bundle install` and `bundle exec fastlane ios beta version:X.Y.Z`
with `ostrom-core` checked out next to this repository. Never commit or paste these files anywhere.

## Already prepared for later

- Android: `companion-android/build.gradle.kts` signs a release build only when `fastlane/keystore.jks` exists and
  `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` are set; otherwise the release build stays
  unsigned. `versionCode` is derived from the version, and `-PostromVersionCode` overrides it for an upload. The Play
  lane (TeamCode's `android beta`) is ported once Ostrom has a Play account.
- Desktop: bundle identifier `run.ostrom.app.desktop`, a fixed Windows upgrade code; installers (DMG, MSI, DEB, RPM)
  refuse a 0.x version, so they come with 1.0.0.

Secrets for later, by whoever owns the accounts:

| Secret | For |
| --- | --- |
| `ANDROID_UPLOAD_KEYSTORE` (base64), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` | Android upload key |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Play upload (fastlane supply) |
| `OSTROM_MACOS_CERTIFICATE_P12`, `OSTROM_MACOS_CERTIFICATE_PASSWORD`, variable `OSTROM_MACOS_TEAM_ID` | macOS Developer ID signing of the desktop app |
| `OSTROM_NOTARY_KEY`, `OSTROM_NOTARY_KEY_ID`, `OSTROM_NOTARY_ISSUER_ID` | macOS notarization |
| `google-services.json`, `GoogleService-Info.plist`, APNs key | Push through FCM and APNs, if the server moves off Expo push |

Windows installers stay unsigned (as TeamCode's): SmartScreen warns on first launch.
