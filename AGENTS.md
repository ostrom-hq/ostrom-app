# ostrom-app contributor instructions

This is the Ostrom app: one Kotlin Multiplatform module with a Compose Multiplatform UI (`companion/`) for iOS, iPad,
Android and the desktop (macOS, Linux, Windows), with thin platform shells (`companion-android/`, `companion-ios/`,
`companion-desktop/`). It is built like TeamCode's companion, from which part of its code is ported (Apache-2.0, see
NOTICE). Apache-2.0, `ee/` under the Ostrom Enterprise License. Why Kotlin Multiplatform rather than the former React Native app:
[decision 0001](docs/decisions/0001-kotlin-multiplatform-companion.md).

**The conventions are in [ostrom-core `docs/CONVENTIONS.md`](https://github.com/ostrom-hq/ostrom-core/blob/main/docs/CONVENTIONS.md) (local clone: `../ostrom-core/docs/CONVENTIONS.md`).** They follow Nathan Fallet's practices (teamcode, asonar,
pkg) and bind every contributor, human or agent. This file only summarizes the most important rules; when in
doubt, the conventions file wins, and it can be updated: re-read it before each pull request.

All repository content (code, comments, docs, commits, pull requests) is in English.

## Specific to this repository

- API models and calls come from ostrom-core: `run.ostrom:ostrom-api` (the contract's models) and
  `run.ostrom:ostrom-client` (the typed client), never a hand-written copy. A missing route or model is added to
  ostrom-core first. CI builds ostrom-core from source at the commit of `gradle/ostrom-core.ref`; move it when the app
  needs a newer core.
- A feature's screen shows only when the server announces its key in `GET /api/v1/capabilities` (`Features`, kept
  equal to core's `ServerFeatures` by a test); a server of another API version is refused and blocks the app.
- How the app works and what remains to port: [docs/companion.md](docs/companion.md). Distribution: TeamCode's fastlane setup
  as is (`bundle exec fastlane ios beta` / `android beta`, run from Nathan's Mac, not CI): TestFlight and Play's
  internal track only, never a review submission or a public release (README, Release; [docs/distribution.md](docs/distribution.md)).
  Never commit or copy a key: `fastlane/key.p8`, `fastlane/*.json` and `*.jks` are git-ignored and stay on that Mac.
- A file ported from TeamCode may keep a second header line `// Ported from TeamCode (Apache-2.0), <path>; see NOTICE.`

## The rules that matter most

1. Read CONVENTIONS.md before each pull request (the Kotlin sections); it wins over habits from other projects.
2. Clean Architecture: `domain/` (models, use cases, repository and service interfaces) imports neither Ktor, the
   typed client nor Compose; `infrastructure/` implements them; `presentation/` holds the screens and their view
   models (`XxxViewModel`, a `StateFlow` of an immutable `XxxUiState`); `di/` wires them with Koin; the platform
   modules (`androidMain`, `iosMain`, `jvmMain`) bind the platform services.
3. Never swallow `CancellationException`: rethrow it first in every broad catch; no `runCatching` around suspend code.
4. Failures reach the screen as `AppException`s worded by `userMessage()`: never a server-provided message.
5. Secrets (tokens, unlock keys) only in the `CredentialStore` (Keychain, Android Keystore, the desktop keyring);
   never in settings, logs or crash reports. Never log tokens, prompts or response bodies.
6. All user-facing text through `Res.string`, in English and French (`LocalizationTest`); the apostrophe stays bare.
7. Tests: TDD. Domain and repositories in `commonTest` (fakes, Ktor `MockEngine`), screens as Compose UI tests on the
   desktop JVM (`jvmTest`, the whole `App()` on a fake server); Kover's gate on the domain must stay green.
8. The demo (`demo/`) answers from memory and creates no HTTP client; a new screen adds its demo content.
9. No simulator or emulator run is required locally: CI builds and tests every platform.
10. Every source file starts with `// SPDX-License-Identifier: Apache-2.0` (`LicenseRef-Ostrom-Enterprise` under `ee/`).
11. CI: actions pinned by SHA; a draft pull request runs no CI.
12. Git: one branch per topic in its own worktree, never push to `main`, Conventional Commits in English, signed off,
    squash-merge after green CI and a review with nothing blocking; evidence in the pull request.

## Coordination

Agents working at night read `~/git/ostrom-hq/.coord/BRIEF.md` first, log their work in `.coord/JOURNAL.md` and put
questions in `.coord/QUESTIONS.md` with their recommendation, then continue with it. Never modify another agent's
branch or worktree, never kill a process you did not start.
