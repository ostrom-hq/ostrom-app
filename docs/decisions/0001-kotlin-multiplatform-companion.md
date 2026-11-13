# 0001. Rewrite the app in Kotlin Multiplatform and Compose, for iOS, Android and the desktop

- Date: 2026-11-13
- Decided by: Nathan Fallet
- Status: accepted

## Context

The company runs on TeamCode today and is to migrate to Ostrom. Its members use TeamCode's companion app every day,
the same app on a Mac, on an iPad and on a phone, with the same features written once. Nathan's condition for the
migration: Ostrom has the same, on iOS, Android and the desktop, and the desktop means macOS, Linux and Windows,
because the team uses all three.

ostrom-app, on 2026-11-13, is an Expo 58 / React Native 0.88 app (about 32,000 lines of TypeScript outside tests).
It targets iOS (tablets included) and Android, plus the web through react-native-web. It covers approving gates
(with biometric confirmation), receipts history, spending and activity. It does not follow or steer agent sessions,
and it has no desktop build.

TeamCode's companion is one Kotlin Multiplatform module with a Compose Multiplatform UI (about 50,000 lines of shared
code in 409 files), with thin platform shells: Android (3 files), iOS (5 files) and the desktop (24 files: menu bar,
packaging, signing and notarization for macOS, Windows and Linux). It depends only on TeamCode's typed client, like
the CLI. It is Apache-2.0.

## Decision

Replace the React Native app with a Kotlin Multiplatform + Compose Multiplatform app built the way TeamCode's
companion is (shared `companion` module, `companion-android`, `companion-ios`, `companion-desktop`), on a typed Kotlin
client in ostrom-core shared with the CLI. Port TeamCode's companion code where Ostrom has the same feature, with
attribution. Keep every feature the React Native app has (gate approvals with biometric confirmation, receipts,
spending, activity, push), so nothing is lost. The React Native sources leave the repository in the same change; git
history keeps them.

## Why not keep React Native

- **No supported desktop target with the same code.** Expo builds iOS, Android and the web. The desktop options are
  react-native-macos and react-native-windows (maintained by Microsoft, outside Expo's managed workflow, with no
  Linux), or wrapping the react-native-web build in Electron (a web page in a desktop shell: no shared native
  components, a different runtime and its own packaging, updates and security surface). None gives macOS, Linux and
  Windows from the code that runs on the phone.
- **The app to reach already exists in Kotlin.** TeamCode's companion does what is required on all five platforms
  today. Porting it costs less than building the same features again in TypeScript, then the desktop on top, and it
  keeps the two products' apps behaving alike during and after the migration.
- **One language with the rest of Ostrom.** ostrom-core, ostrom-server and ostrom-cli are Kotlin Multiplatform: the
  app reuses the contract's Kotlin models and the typed client instead of a generated TypeScript copy of the contract.

## Options set aside

- Keep React Native for mobile and write a separate desktop app: two UIs to write and keep alike, the opposite of the
  requirement.
- React Native + react-native-macos + react-native-windows: no Linux, outside Expo, three native shells to maintain.
- Electron around react-native-web: not the same app on the desktop, heavier, and a second release pipeline.
- Flutter or another stack: a rewrite as well, without TeamCode's code to port and without the shared Kotlin client.

## Consequences

- The app is a Gradle project; its CI runs the JVM and Android host tests and Compose UI tests on the desktop, like
  TeamCode's.
- Push notifications follow the server side Ostrom serves (Expo push today); moving to APNs and FCM directly is part of
  the notifications parity work.
- Store and desktop distribution (fastlane lanes, Android signing, Apple signing and notarization, Windows and Linux
  packages) need certificates and secrets that Nathan creates.
