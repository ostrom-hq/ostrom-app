# 0001. Rewrite the app in Kotlin Multiplatform and Compose, for iOS, Android and the desktop

- Date: 2026-11-13
- Requirement: Nathan Fallet (the team needs one identical app on mobile and the desktop)
- Technology choice: made by Claude (Claude Code, the AI agent porting TeamCode's features to Ostrom), not by Nathan,
  who left the technology open
- Status: accepted

## Context

The company runs on TeamCode today and is to migrate to Ostrom. Several members of the team reach their agents through
TeamCode's companion app every day, on a Mac or another desktop, on an iPad and on a phone. The requirement Nathan set
for the migration, and the only one: the team gets the interface to its agents on mobile and on the desktop with an
identical app everywhere (iOS, iPad, Android, and the desktop on macOS, Linux and Windows, because the team uses all
three), the same features, only the layout adapting to the screen size (a phone layout, a wider iPad and desktop
layout). He left the technology open: keep React Native and add a desktop target, rewrite in Kotlin Multiplatform, or
anything else.

ostrom-app, on 2026-11-13, is an Expo 58 / React Native 0.88 app (about 32,000 lines of TypeScript outside tests).
It targets iOS (tablets included) and Android, plus the web through react-native-web. It covers approving gates
(with biometric confirmation), receipts history, spending and activity. It does not follow or steer agent sessions,
and it has no desktop build.

TeamCode's companion is one Kotlin Multiplatform module with a Compose Multiplatform UI (about 50,000 lines of shared
code in 409 files), with thin platform shells: Android (3 files), iOS (5 files) and the desktop (24 files: menu bar,
packaging, signing and notarization for macOS, Windows and Linux). It depends only on TeamCode's typed client, like
the CLI. It is Apache-2.0.

## Decision

Claude chose to replace the React Native app with a Kotlin Multiplatform + Compose Multiplatform app built the way TeamCode's
companion is (shared `companion` module, `companion-android`, `companion-ios`, `companion-desktop`), on a typed Kotlin
client in ostrom-core shared with the CLI. Port TeamCode's companion code where Ostrom has the same feature, with
attribution. Keep every feature the React Native app has (gate approvals with biometric confirmation, receipts,
spending, activity, push), so nothing is lost. The React Native sources leave the repository in the same change; git
history keeps them.

## Why Claude did not keep React Native

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

## The options compared

| Option | For | Against |
| --- | --- | --- |
| **Kotlin Multiplatform + Compose Multiplatform** (chosen) | One UI and one logic for iOS, iPad, Android and the three desktops: the iPad and desktop apps come with the same code, only the layout adapts to the window size class. TeamCode's companion already exists in this stack and does what the team uses (porting instead of rebuilding). Same language as ostrom-core, ostrom-server and ostrom-cli: the contract's Kotlin models and one typed client shared with the CLI. Native binaries on every platform, no web view for the UI. | A rewrite: the React Native app's 32,000 lines are not reused as code (their behaviour is ported). Compose on iOS is younger than SwiftUI or React Native on iOS; a few iOS-specific pieces stay in Swift (the Xcode shell, keychain, push). Contributors need Kotlin rather than TypeScript. |
| Keep React Native (Expo), add react-native-macos and react-native-windows | Keeps the existing code and the TypeScript skills; mature on iOS and Android. | No Linux at all. Both desktop ports are maintained outside Expo (no managed workflow, no Expo modules there): three native shells to keep in step, and each library used must support each port. Not the "same app everywhere" with equal confidence. |
| Keep React Native for mobile, wrap react-native-web in Electron for the desktop | Linux, macOS and Windows; reuses part of the TypeScript. | The desktop app is a web page in a Chromium shell: different runtime, different behaviour from the phone, heavier, its own packaging, updates and security surface, and the React Native code has to work on react-native-web too. |
| A separate desktop app (native or web) beside the React Native mobile app | Each app can use its platform's best tools. | Two UIs to write and keep alike: the opposite of the requirement. |
| Flutter (or another cross-platform toolkit) | One UI for mobile and desktop as well. | A rewrite too, without TeamCode's code to port and without the shared Kotlin client; a fourth language in Ostrom. |

Kotlin Multiplatform is the only option that gives the identical app on every required platform, from one code base,
while reusing what already exists (TeamCode's companion, Ostrom's Kotlin contract and client). Its main cost, a
rewrite, is lower than it looks because the app to reach is already written in that stack.

## Consequences

- The app is a Gradle project; its CI runs the JVM and Android host tests and Compose UI tests on the desktop, like
  TeamCode's.
- Push notifications follow the server side Ostrom serves (Expo push today); moving to APNs and FCM directly is part of
  the notifications parity work.
- Store and desktop distribution (fastlane lanes, Android signing, Apple signing and notarization, Windows and Linux
  packages) need certificates and secrets that Nathan creates.
