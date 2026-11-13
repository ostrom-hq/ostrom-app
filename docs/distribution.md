# Distribution

**Pending a store account.** Ostrom has no App Store, Google Play or Apple Developer account yet (2026-11-13: Nathan
settles with Lyes which account the app goes on; it is neither ControlResell's nor a personal one). Until then the app
is built and tested in CI, and its builds are **unsigned**:

| Platform | What CI builds | Where |
| --- | --- | --- |
| Android | Debug APK (debug key) | `ci` job on `main`, artifact `ostrom-android-debug` |
| iOS | Simulator build, `CODE_SIGNING_ALLOWED=NO` | `ios` job on `main` |
| macOS, Linux, Windows | Runnable jar of each system (`packageUberJarForCurrentOS`) | `desktop` jobs on `main`, artifacts `ostrom-desktop-<system>` |

No certificate, provisioning profile, keystore or store listing is created until the account exists.

## Already prepared

- Android: `companion-android/build.gradle.kts` signs a release build only when `fastlane/keystore.jks` exists and
  `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD` are set; otherwise the release build stays
  unsigned. `versionCode` is derived from the version, and `-PostromVersionCode` overrides it for an upload.
- iOS: `companion-ios/Configuration/Config.xcconfig` leaves `OSTROM_DEVELOPMENT_TEAM` empty; the bundle identifier is
  `run.ostrom.app` (plus `TEAM_ID` for a contributor's own team).
- Desktop: bundle identifier `run.ostrom.app.desktop`, a fixed Windows upgrade code; installers (DMG, MSI, DEB, RPM)
  refuse a 0.x version, so they come with 1.0.0.

## What is needed once the account exists

The release lanes are ported from TeamCode's (`fastlane/`, its `build.yml` desktop jobs) then. Secrets to create, by
whoever owns the account:

| Secret | For |
| --- | --- |
| `ANDROID_UPLOAD_KEYSTORE` (base64), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` | Android upload key |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Play upload (fastlane supply) |
| `APP_STORE_CONNECT_API_KEY_ID`, `APP_STORE_CONNECT_ISSUER_ID`, `APP_STORE_CONNECT_API_KEY` (.p8) | App Store Connect upload (fastlane pilot) and notarization |
| `MATCH_GIT_URL`, `MATCH_PASSWORD` (or the distribution certificate and profile as secrets) | iOS signing |
| `OSTROM_MACOS_CERTIFICATE_P12`, `OSTROM_MACOS_CERTIFICATE_PASSWORD`, variable `OSTROM_MACOS_TEAM_ID` | macOS Developer ID signing of the desktop app |
| `OSTROM_NOTARY_KEY`, `OSTROM_NOTARY_KEY_ID`, `OSTROM_NOTARY_ISSUER_ID` | macOS notarization |
| `google-services.json`, `GoogleService-Info.plist`, APNs key | Push through FCM and APNs, if the server moves off Expo push |

Windows installers stay unsigned (as TeamCode's): SmartScreen warns on first launch.
