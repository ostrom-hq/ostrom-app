# The app

One Kotlin Multiplatform module, `companion/`, holds the whole app: its domain, its calls to the server, its Compose
Multiplatform screens. Three thin shells run it: `companion-android/` (an activity), `companion-ios/` (an Xcode project
that embeds the framework `Companion`) and `companion-desktop/` (a window). It is built like TeamCode's companion
(`companion/`, `companion-android/`, `companion-ios/`, `companion-desktop/` of
[TeamCode](https://github.com/nathanfallet/teamcode), Apache-2.0); files ported from it say so in their header.

## Layers

| Layer | Package | What it holds |
| --- | --- | --- |
| Domain | `run.ostrom.app.domain` | Models (`Account`, `ServerInfo`, `ServerCapabilities`, `AppException`), use cases (sign-in, sign-out, refresh, organizations), repository and service interfaces. No Ktor, no client, no Compose. |
| Infrastructure | `run.ostrom.app.infrastructure` | The repositories over ostrom-core's typed client (`OstromClient`, one per account, `OstromClientProvider`), failures translated to `AppException` (`AccountCalls`), settings (accounts metadata, theme), credential stores. |
| Presentation | `run.ostrom.app.presentation` | Screens, view models (`StateFlow` of a `UiState`), navigation, theme (Ostrom's garnet), components ported from TeamCode. |
| Demo | `run.ostrom.app.demo` | The built-in demo: an account on no server, answered from memory; routers send the demo account's calls there and every other account's to its server. |
| Platforms | `androidMain`, `iosMain`, `jvmMain` | Secure storage, settings, HTTP engine, the browser sign-in of the phones. |

## Sign-in

- **Phones** (Android, iOS) sign in with GitHub in the system browser: a Custom Tab on Android, an
  `ASWebAuthenticationSession` on iOS. The app draws a PKCE verifier and a state, opens
  `GET /api/v1/auth/github/start` with `redirect_uri=ostrom://auth/callback`, takes the return only if its state is the
  latest attempt's, and exchanges the code (`POST /api/v1/auth/mobile/token`) for a 30-day app token and its unlock key.
- **Computers** sign in with the device flow of `ostrom login` (`POST /api/v1/auth/device`, then
  `POST /api/v1/auth/device/token` every interval, slower on `slow_down`): the server only sends a browser back to
  the phones' `ostrom://auth/callback`. The page to enter the code on must be GitHub's or of the server's own origin
  (or its console's, `app.` for `api.`).
- Every new session's capabilities are read before it is kept: a server whose API version this app does not speak
  is refused, and the new session revoked. The organization the calls act in (`Ostrom-Org`) is the only membership,
  or the one `GET /auth/me` names; a member of several chooses it in Settings.
- Tokens and unlock keys live in the platform's secure storage (Keychain, Android Keystore AES-GCM, the desktop
  keyring; in memory where a desktop has none, which Settings says); account metadata in the settings.
- Several accounts, on one server or several, side by side; a session the server ended stays listed as signed out
  and asks to sign in again.

## Capabilities

`GET /api/v1/capabilities` decides what shows: a tab or a screen appears only when the server announces its feature
key (`Features`, equal to ostrom-core's `ServerFeatures`, a test checks it). A server of another API version, or one
too old to have the route, replaces the app with a blocking screen whose only action is to sign out. The server is
asked again when the app comes back to the foreground, at most every ten minutes; an unreachable server changes
nothing.

## Approvals

The first tab (`gates.v1`) lists what waits for the person's yes, then what was answered lately. A row only opens the
request (`RequestRoute(accountId, id)`, which a push or a link can open too). The request shows who asks, the gate, the
command, the commit, the size of the change, the commits, checks and evidence before any button.

- The answer is bound to the digest of what was first read: a refresh that changes the digest, who asks, the command or
  the gate holds every answer until the person taps "I read it again".
- No yes when the change is not shown in full (`diffTruncated`) or the server does not take the person's yes
  (`you.canApprove`), said in the person's language. A no needs its reason, which the agent reads.
- Before an answer the device unlocks (`PhoneUnlock`): the biometrics (`BiometricGate`: BiometricPrompt with the device
  credential on Android, `LAContext` `deviceOwnerAuthentication` on iOS) give back the unlock key kept at sign-in, and
  `POST /auth/mobile/unlock` records it. A yes always unlocks, bound to its request. A no reuses an unlock younger than
  four minutes, unless the action is not low risk. Going to the background ends it. The key itself stays in the
  credential store (Keychain, Android Keystore); the prompt is what the app asks before reading it.
- The desktop has no biometrics and no unlock key (device-flow sign-in): its answer goes with its token, and the
  server decides. A yes the server wants confirmed (`403 step_up_required`) is sent to the console.
- Every refusal is worded by its key, never the server's text. `step_up_required` with `requestBound` asks to unlock
  again, without it to open the console (`<origin>/yes/<id>`, whose address is shown to copy when no browser opens). A
  self-approval, an answer already given, `shown_digest_mismatch`, `org_required` and offline each have their sentence.

## Tests

- `companion/src/commonTest`: use cases with fakes, repositories against Ktor's `MockEngine`.
- `companion/src/jvmTest`: Compose UI tests of the whole `App()` on the desktop JVM against a fake server (sign-in,
  sign-out, a server too new, the demo), and the translations (`LocalizationTest`).
- `RealServerJourneyTest` (jvmTest) drives the whole app against the real server: ostrom-server's `app-journey`
  workflow starts a published server image, prepares it with `scripts/real-server-session.sh` (two pushes to main
  waiting for the person, a budget passed, the phone's session and unlock key) and runs this test with that session in
  `OSTROM_E2E_*`; it says yes and no, reads the spending, the activity and the account, and the workflow checks the
  gates and their signed receipts. Without `OSTROM_E2E_SERVER` it is skipped.
- `testAndroidHostTest` runs the common tests on Android's host JVM. Kover's gate covers the domain.

## Parity

What the React Native app did, what TeamCode's companion does that an Ostrom server serves, and where each stands.
"Feature" is the capability key that gates it.

| Area | Feature | Status |
| --- | --- | --- |
| Sign-in (phone browser, desktop device flow), accounts, organizations, secure storage, capabilities check, incompatible server, settings with versions, theme, English and French, demo | none | Done |
| Approvals inbox and request, yes after a biometric unlock bound to the request (`POST /auth/mobile/unlock` with `requestId`) | `gates.v1` | Ported (see [Approvals](#approvals)) |
| Waiting for you (waits asked of the person, answer with actions and values) | `waits.v1` | Ported from TeamCode's `WaitsScreen` (open, answered and cancelled filters, in-progress timers and webhooks, tab badge). Loaded at each return to the app until the server serves its event feed; no link to the session (no sessions screen yet), no withdraw (no route) |
| History: receipts, verified on the device | `receipts.v1` | To port |
| Spending | `budgets.v1` | Ported (week and month, budget bar, budgets passed, by agent, repository and person) |
| Activity | none | Ported (newest first, paged; a line about an approval opens its request on a server with `gates.v1`) |
| Push registration and notification preferences | `notifications.v1`, `notifications.push.v1` | To port (the server sends Expo push today) |
| Artifacts gallery and viewer | `artifacts.v1` | To port from TeamCode |
| Sessions (list, transcript, follow live) | `sessions.v1` | To port from TeamCode, once ostrom-core's client has the session routes |
| Todos, workflows, automations, MCP servers, GitHub link, sharing | `todos.v1`, `workflows.v1`, `automations.v1`, `mcp.v1`, `github.link.v1`, `sharing.v1` | Later, each with its client routes in ostrom-core |
| Desktop: menu bar icon, the CLI's account, update banner | none | To port from TeamCode's `companion-desktop` |
| Store builds (TestFlight, Play internal track) | none | Ported: TeamCode's fastlane lanes as is ([distribution.md](distribution.md)) |
| Desktop distribution (signed installers) | none | With 1.0.0 and a Developer ID ([distribution.md](distribution.md)) |
