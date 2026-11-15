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

## Desktop installers

Ported from the `desktop` job of TeamCode's `build.yml` and its `companion-desktop/build.gradle.kts`. The workflow
`release-desktop.yml` is started by hand (`workflow_dispatch`) with a `version` (MAJOR.MINOR.PATCH, 1.0.0 or later:
DMG, MSI, DEB and RPM installers refuse a 0.x). It builds on four runners and uploads one workflow artifact per target
(`ostrom-desktop-<target>`, kept 14 days), and nothing more unless asked:

| Target | Runner | Files (`ostrom-desktop-<version>-<target>.*`) |
| --- | --- | --- |
| `macos-arm64` | `macos-latest` | `.zip` (the `.app`), `.dmg` |
| `windows-x64` | `windows-latest` | `.msi` (per user, no administrator rights) |
| `linux-x64`, `linux-arm64` | `ubuntu-24.04`, `ubuntu-24.04-arm` | `.tar.gz` (the app folder), `.deb`, `.rpm` |

Each target also has a `SHA256SUMS-<target>` file. With `release_tag` set to an **existing** release, the `attach` job
uploads the files and one `SHA256SUMS-desktop` to it; it never creates or publishes a release.

**The signed manifest.** The `manifest` job assembles `desktop-manifest.json` (`product: desktop`, the version, the
source commit, and for each target the file name, SHA-256 and size of the package `ostrom ui` installs: the zipped
`.app`, the `.msi`, the `.tar.gz`; the DMG, DEB and RPM are for people downloading by hand), as TeamCode's
`release-desktop.py` does. With `release_tag` it signs it with the CLI's release key, the way ostrom-cli signs
`latest.json` (Ed25519 over the raw bytes, base64 in `desktop-manifest.json.sig`), and checks the signature against the
pinned root `release/trust/ostrom-release-2026.pem` before anything is attached. The key is the repository secret
`OSTROM_RELEASE_SIGNING_KEY` (the value of ostrom-cli's `RELEASE_SIGNING_KEY`, a PEM PKCS#8 Ed25519 key). `ostrom ui`
downloads the manifest of the CLI's own version from this repository's release of that tag and authenticates it with the
roots compiled into the CLI. The app carries its version in `ostrom-desktop-version` in its resources, which `ostrom ui`
reads.

**Signing and notarization (macOS).** Without the secrets below the macOS build is unsigned (Gatekeeper warns), which is
what a validation run wants. Set `require_signing` for a real release: the job then fails unless every secret exists.
With them, the job imports the Developer ID into a temporary keychain, `companion-desktop` signs the app (and the native
library inside the keyring's jar, as TeamCode does), `scripts/notarize-macos.sh` notarizes the app and the DMG with
`notarytool` and staples them, with retries on Apple's service failures, then the temporary keychain is deleted.
Windows installers are not signed (no paid signing service): SmartScreen warns on first launch.

| Name | Kind | Content |
| --- | --- | --- |
| `OSTROM_MACOS_CERTIFICATE_P12` | secret | the "Developer ID Application" certificate with its private key, a `.p12` encoded in base64 |
| `OSTROM_MACOS_CERTIFICATE_PASSWORD` | secret | the `.p12` password |
| `OSTROM_NOTARY_KEY` | secret | the App Store Connect API key (`.p8`) used by `notarytool`, as text |
| `OSTROM_NOTARY_KEY_ID` | secret | that key's ID |
| `OSTROM_NOTARY_ISSUER_ID` | secret | the issuer ID of the App Store Connect team |
| `OSTROM_RELEASE_SIGNING_KEY` | secret | the release key that signs `desktop-manifest.json` (same value as ostrom-cli's `RELEASE_SIGNING_KEY`); needed with `release_tag` |
| `OSTROM_MACOS_TEAM_ID` | variable | the Apple Team ID (`Z8T3MHFW48`), public |

The build reads `OSTROM_MACOS_SIGNING_IDENTITY` and `OSTROM_MACOS_SIGNING_KEYCHAIN` (set by the job) to sign, and
`OSTROM_NOTARIZATION_APPLE_ID` / `_PASSWORD` / `_TEAM_ID` to let Gradle notarize on a Mac without the job (not used by
the workflow). Icons are in `companion-desktop/icons/`, made from the phones' icon (the macOS shape for the `.icns`).
