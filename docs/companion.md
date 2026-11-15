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

## Tabs

A wide window (a tablet, the desktop) has a rail with every tab its server serves. A phone's bar has at most five,
each with its label, as TeamCode's: the served ones of Approvals, Waiting, Sessions and Workflows, then the other served
tabs in their order while there is room, and Settings last. The tabs left out (History, Spending, Activity, Artifacts
when everything is served) are rows of a section at the top of Settings, each opening its screen above the tabs with
back to Settings. A tab asked for by a notification, a link or the desktop's menu bar opens there too on a phone; a
tab shown on a wide window that narrows moves there.

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

## History

- The receipts are read as the server wrote them (`listRaw`) and verified on the device, off the main thread, by
  ostrom-core's `ReceiptVerifierImpl`: a schema would drop the fields this version does not know and a genuine receipt
  would read as modified. A receipt this app cannot display still has its row and is still checked.
- Trust on first view: the server's receipt keys are pinned in the secure store (`receipt-pins:<origin>`, the raw key
  by key id). A key served later is trusted only through a rotation signed by a trusted key, transitively
  (`ostrom-receipt-key-rotation/v1\n` + the new key's raw hex, Ed25519). Pins that cannot be read trust nothing.
- A check run counts as run by Ostrom or the CI (`isolated_runner`, `ci`) only on a verified receipt.
- A session's record links to its receipt, and each of its gate answers to its own: the receipt opens by its id
  (`ReceiptRoute`, `getRaw`, `GET /api/v1/receipts/{id}`) and is verified exactly as a row of the History tab, with the
  same pinned keys. A receipt answered under another id than the one asked reads as gone. TeamCode has no receipts: the
  link follows how its record links open a record by id (a session, an artifact).
- Android below API 33 has no Ed25519: the app installs Bouncy Castle at start, in place of the platform's "BC".

## Push

Ported from TeamCode's companion (`PushPlatform`, `PushCipher`, `SyncPushUseCase`, `ReadPushUseCase`), FCM only.

- At every start, when FCM rotates the token, when an account signs in and when its organization changes, each
  signed-in account whose server announces `notifications.push.v1` gets `POST /api/v1/push-devices` with
  `{pushToken, provider: "fcm", platform, locale?, publicKey}`. The public key is the phone's P-256 key
  (SubjectPublicKeyInfo DER, base64), made once: on Android in the Keystore-sealed credential store
  (`push-device-key`), on iOS in a Keychain access group shared with the notification service extension. The
  account's member id is read once registered (`GET /auth/me`), since a push names its server, organization and member.
- The FCM data is `{p, v: "1"}`: `p` = base64(`0x01` | ephemeral point (65 B) | nonce (12 B) | AES-256-GCM ciphertext
  and tag), the key HKDF-SHA256 of the ECDH secret with salt = ephemeral point | phone point and info `ostrom-push-v1`;
  the plaintext is ostrom-core's `PushPayload`. Android's messaging service decrypts it and shows it only for a
  signed-in account of that server (its origin normalized), organization and member.
- A tap opens `ostrom://notification?origin=…&org=…&member=…[&approval=…][&session=…][&wait=…]` (`NotificationLinks`,
  built from the payload's `approvalId`, `sessionId` and `waitId`): the account becomes the active one and its request
  opens (`RequestRoute`; an answer still asks for the unlock). A push about a session (`waiting`, `turn_failed`) opens
  that session's record (`SessionRoute`) when the server announces `sessions.v1`, and the Waiting tab otherwise; a
  wait outside a session opens the Waiting tab, where that wait is; a test push opens the account.
- Before a sign-out the device registered in this process is deleted (`DELETE /api/v1/push-devices/{id}`), best effort;
  the server also forgets the phones of a session it revokes.
- On iOS the relay sends a generic alert ("Ostrom" / "New activity in Ostrom", `mutable-content`) with `p`: the
  notification service extension (`companion-ios/NotificationService`) decrypts it with CryptoKit
  (`companion-ios/Push/PushCrypto.swift`, shared by both targets) and replaces the alert with the real title and body,
  threaded by request; a payload it cannot read keeps the generic alert. The key is in the Keychain group
  `run.ostrom.app.push`, the only one the extension lists: it never sees the accounts' tokens or unlock keys. The
  alert permission is asked when the first account whose server offers push is registered.
- Push is on only in a build that carries the Firebase project (`companion-android/google-services.json`,
  `companion-ios/Ostrom/GoogleService-Info.plist`, both git-ignored); without it the app builds and runs without push. Android 13+ asks the notification permission once,
  after the first sign-in.

## Artifact links

`ostrom://open?url=<page>` (TeamCode's `teamcode://open?url=`, TC-COM-29) opens an artifact's console page in the app's
viewer (`OpenArtifactLinkUseCase`). The page must be `<origin>/artifacts/<id>` (a trailing slash, a `v=<version>` and
other query parameters allowed), where the origin is a signed-in account's server or its console (`app.` for `api.`,
as the sign-in pages are checked). That account becomes the active one (the active account first when several are on
that server), and the artifact opens above its tabs. Anything else is refused with a sentence and nothing is opened or
asked of any server: not an artifact page (another path, an `http` origin off this machine, credentials in the address,
a malformed escape, a bad version), a server no account is on, a server whose accounts are all signed out. The content
origin's pages (`/a/<id>/<ticket>/`) are not taken: the app does not know a server's content origin before a ticket names it.

Android takes the link with an intent filter (`ostrom://open`), iOS with `onOpenURL` (the `ostrom` scheme was already
registered). The desktop shell receives no URL from the system (registering a scheme needs installer changes), so
it does not handle the link yet.

## Live updates

The organization's feed (`GET /api/v1/events`, `events.v1`) is one connection per account for the whole app
(`OrgFeed`, ported from TeamCode's `SessionFeed`): it opens with the first screen that follows it and closes five
seconds after the last one left, so moving between screens does not reconnect. It asks the server for the union of the
types the app reads, each only when the server has its feature (`OrgChange.typesFor`): the waits'
(`cloud.wait.created`, `.resolved`, `.cancelled`), `cloud.workflow.run.updated` and `cloud.todo`; there is no session
event type. Each follower keeps its types: the waits and their badge (`FollowWaitsUseCase`), the sessions list
(`FollowSessionsUseCase`), a session's todo (`FollowTodoUseCase`, its session's `cloud.todo` only), the runs lists and a
run (`FollowWorkflowRunsUseCase`). A follower that joins an open feed is told to load at once; every follower loads
again after a gap or a connection that could not resume, and the screens load at each return and on refresh. From a
server without the feed, each screen loads once, then again on return and on refresh.

The server does not publish the run and todo events yet: until it does, the runs and the todo are current at the
feed's opening, after a gap, on return and on refresh, and become live by themselves once it does.

## Cloud sessions

The person's cloud sessions (`runners.cloud.v1`, ostrom-core's `client.cloudSessions`) are in the Sessions tab, as
TeamCode shows its cloud sessions in its own: with `sessions.v1` too, a switch under the title picks the agent sessions
or the cloud sessions; with only one of the two, the tab shows it. The list is newest first and keyset-paged; it loads
at each return and on refresh, as does a record: the server publishes no cloud session event, and the app does not poll.

- **Record.** Status, agent and model, the single repository and ref or the preparation recipe (repositories, working
  directory, setup commands, required variables, GitHub requirement, Git e-mail domain), times and the plan's limits.
  While it is creating or resuming, the step the runner reports (`configuring_git`, `cloning_repositories`,
  `fetching_repositories`, `running_setup`; any other reads as preparing the workspace). A failure is worded by its
  code; a `preparation_failed` shows the server's sentence (which step failed, never a command's output) and says to
  resume when `retryable`, otherwise that the recipe or the variables must be fixed in the console. This is the one
  accepted exception to "never the server's text" (AGENTS.md): the message is structured (step, index, exit code) and
  never holds a command's output.
- **Actions.** Pause (while it runs or is on its way), Resume (on a paused session not already resuming), Stop (asked first: the workspace
  is deleted). Each takes the server's answer, then reads the session again. A refusal is worded by its key
  (`invalid_request`, `payment_required`, `conflict`, `forbidden`, `profile_required`, `profile_unassigned`,
  `cloud_profile_not_found`, `image_not_allowed`, `cluster_unavailable`, `namespace_not_allowed`), never with the
  server's text.
- **GitHub access.** With `runners.cloud.github.v1`, a record whose `githubAccess` the server states (and that has not
  ended) shows a switch: Mine (`owner`, the default), Shared (`shared`, the owner's account with the terminal shared)
  or None (`none`, public repositories only), sent with `PUT .../github`. The sessions listed are the person's own,
  so the person is the owner; `github_owner_only` (403, an agent or a service key) is worded anyway. Taking the access
  away from a session that is not paused is refused by the server (`409 session_not_paused`): the app sends it and
  words the refusal by its key ("Pause the session before removing its GitHub access"), never the server's text.
  TeamCode's companion has no such control; it is Ostrom's own.
- **New cloud session.** The agent, a name, a model and a first message, checked as the server checks them. With
  `runners.cloud.environment.v1` the form takes the recipe (the fields of `CreateCloudSessionRequest.environment`);
  without it, the single repository and ref. No profile or image is chosen: the server picks the person's only profile,
  and asks for one (`profile_required`, worded) when there are several.
- **Transfers** (`runners.cloud.transfer.v1`), ported from TeamCode's `TransferSheet`, `TransfersScreen`,
  `TransferViewModels`, `TransferUseCases` and `RemoteTransferRepository` over ostrom-core's `client.cloudTransfers`.
  A button in the Sessions tab's cloud list opens the transfers (to me, from me): accept or decline an offer made to the
  member, withdraw one they made, each followed by a fresh read when the server refuses (decided or expired meanwhile).
  A running, paused, pausing or resuming session the member owns shows "Transfer…" on its record: a sheet picks a member
  from the recipients' list (by login, the owner left out), optionally resumes the session for them, and sends it under
  an idempotency key kept while the form is unchanged. The server decides whether it is an offer (a member's) or taken at
  once (an administrator's), and the sheet says which. A session changing owner reads "Transferring". The server's
  messages are never shown, so the recipient problems of a `422 recipient_ineligible` (only in its message) read as one
  sentence; a failed transfer's own failure code is worded. Not ported: TeamCode's typed member ID, member list for
  administrators and "pause at once" option (Ostrom's contract has neither), the transfer event in a transcript (it
  shows as a system line), and the demo's transfers (the demo announces none, as TeamCode's).
- **A conversation session** (`mode: conversation`, `runners.cloud.conversation.v1`) opens on TeamCode's conversation,
  ported from its `SessionScreen`, `SessionViewModel`, `TranscriptViews`, `TranscriptItems`, `Subagents`, `CodeBlocks`
  and `PermissionCard`, over ostrom-core's `cloudTranscripts` and `cloudMessages`: the newest page of the transcript
  (Markdown, code blocks with Copy, tool calls folded), older pages on demand, the live stream after the newest event
  (it ends with the session), the composer (a message under its idempotency key, retried with it, waking a paused
  session), Interrupt (one at a time: Ostrom's route takes no idempotency key), and the record (overview, actions,
  GitHub access, recipe) behind the summary at the top. Each further part shows with its key: the permission cards
  (`runners.cloud.permissions.v1`: questions one at a time, a tool use allowed or denied, an answer retried under the
  same key), Bypass and its revocation (`runners.cloud.permissions.bypass.v1`, the owner's), the model and the effort
  (`runners.cloud.model.v1`, `runners.cloud.effort.v1`, typed: Ostrom serves no model list), background tasks and their
  stop (`runners.cloud.tasks.v1`), and a feed per subagent with the server's list of subagents
  (`runners.cloud.transcript.feeds.v1`). The new session form offers a conversation for claude and codex. The header is
  read again every 10 s (TeamCode: 30 s). The demo has one conversation session. Files (`runners.cloud.files.v1`,
  TeamCode's paperclip): the organization's policy is read once the screen shows; the paperclip offers the photo
  library, the camera and documents on a phone (photos reduced to 2048 px on their longer side, a HEIC photo sent as a
  JPEG), a file dialog on the desktop, plus drag-and-drop on the window and a pasted file or screenshot in the message
  field; each file is checked against the policy, then streamed at once through ostrom-client (progress, retries under
  one idempotency key, a new key after the stored SHA-256 differed); the message names the uploaded files and may then
  have no text. The demo announces no files. Not ported: attachments, live text deltas (`runners.cloud.transcript.live.v1`: TeamCode's companion shows final messages
  only), TeamCode's todo tab in the subagents panel (a cloud session has no todo), dictation.
- **Not in the app.** A terminal session's terminal (a WebSocket the app does not open).

## GitHub link

With `github.link.v1`, Settings has a GitHub row (under the setup rows) that opens the member's link, ported from
TeamCode's `GitHubLinkScreen`, `GitHubLinkViewModel`, `GitHubLinkUseCases` and `RemoteGitHubLinkRepository` over
ostrom-core's `client.githubLink`. The capability is read again from the server when the screen opens.

- **States.** Not linked (with a note when an administrator has not configured the instance's GitHub App, `appConfigured`
  false: nothing can be linked), linked as `<login>`, reconnect required (link again), and a state of a newer server
  (link again or unlink). A server without the capability shows a note instead.
- **Device flow.** Link starts it (`POST /github/link/device`), copies the code, opens GitHub's page once ("Open GitHub
  again" and "Copy the code" stay), and polls (`POST /github/link/device/{id}`) at the interval the server returns,
  kept between 1 and 60 seconds. Linked ends the flow and reads the account; expired and denied say so and offer to
  start again; a status or an attempt the server no longer knows (`github_link_not_found`) reads as expired. Three failures
  in a row of the server, or of GitHub for the server (`github_unreachable`), are waited out; any other ends the flow.
  Cancel stops the polling. Unlink (`DELETE /github/link`) removes the link and revokes its token at GitHub (the server does).
- **Never a token.** No call returns a GitHub token and the app reads none; it shows the account name only.
- **Refusals** by key, never the server's text: `github_app_not_configured`, `github_device_flow_disabled`,
  `github_app_misconfigured`, `github_account_in_use`, `github_unreachable`.
- **Demo.** The demo announces both GitHub capabilities: its device flow shows a code, then links "ada-lovelace" in memory.
- Another member's link (`list`, `member`, `unlinkMember`, owners and admins) is not in the app.

## Desktop

`companion-desktop/` adds what only makes sense on a computer, ported from TeamCode's `companion-desktop`.

- **Menu bar icon.** The app has an icon in the menu bar (the notification area on Windows and Linux) with Open and
  Quit; activating it shows the window. Closing the window keeps the app there; where the desktop has no tray (some
  Linux desktops), closing the window quits. On macOS a click on the Dock icon shows the hidden window again. The
  window reopens where it was left, at its size, maximized or not, checked against the screens connected then
  (`WindowPlacementStore`). The icon is the phones' (`companion-ios`'s AppIcon), for the window, the Dock and the
  menu bar; the macOS and Windows installers keep the default one until an `.icns` and an `.ico` of it exist.
- **Menu bar.** File: Add an account (Cmd/Ctrl+N), Close window (Cmd/Ctrl+W). View: each tab in its order
  (Cmd/Ctrl+1 to 8, in the order of every tab; one the server does not serve shows the first tab) and Settings (Cmd/Ctrl+,). The menu sends
  `AppCommands`, which the navigation follows.
- **The CLI's account.** When the `ostrom` CLI of this computer is signed in, its session is an account of the app
  (`Account.CLI_ACCOUNT_ID`, a "CLI" chip in Settings), kept equal to what the CLI says (`CliAccountSync`): the CLI's
  folder (`$OSTROM_HOME`, else `$XDG_CONFIG_HOME/ostrom`, else `~/.config/ostrom`, as the CLI resolves it, the login
  shell's variables included) is checked every 2 s by the stamps (modification time, size, file identity) of
  `config.toml` (its `server`) and `credentials.json` (an entry per server signed in). A change adds the account when
  the CLI signs in, describes it again when it signs in to another server, removes it when it signs out. It is
  described with the CLI's token (`GET /auth/me`, `GET /capabilities`); a `401` lists it as signed out until the CLI's
  files change, any other failure is tried again after 60 s. The same person on the same server signed in in the app
  is merged into it (that app session is revoked). The token is read from the OS secret store where the CLI keeps it
  (service `run.ostrom.cli`, account the server URL), only for the server the account describes, and kept in memory
  while the CLI's files do not change. The app never writes the CLI's files or its secret: signing that account out
  runs the CLI's own `ostrom logout --server <url>`, and only when no CLI is found does the app revoke the session on
  the server, as any sign-out. Signed out, the account says to run `ostrom login` in a terminal.
  - On macOS the Keychain may ask once to let Ostrom read the CLI's item: choose Always Allow.
  - Not on Windows: java-keyring names and encodes Credential Manager entries otherwise than the CLI, so the app cannot
    read its token there. Nor on a Linux without Secret Service, where the CLI keeps its token in an encrypted file.
- **Update banner.** At the top of the signed-in app, the banner checks the installed CLI with `ostrom update --check`
  at start, then every 6 hours, at most once an hour across restarts, and reads its two answers
  (`✓ ostrom X is up to date.`, `! ostrom Y is available. Run …`; anything else shows nothing, and so does a failed
  check). "Update" runs `ostrom update`, its lines shown as they come, then says which version the CLI now reports; a
  failure says why with the CLI's last lines and offers to try again. A version closed with "Not now" does not show
  again; a newer one does. The CLI is found where its installers put it (`$OSTROM_INSTALL_DIR`, else `~/.ostrom/bin`;
  Homebrew's and Scoop's folders), then on the `PATH`, the login shell's included. The app only runs the CLI: it
  never downloads or verifies a release itself, and it does not update itself (no desktop release is published yet; the installers are built by hand, see [distribution](distribution.md)).

## Tests

- `companion/src/commonTest`: use cases with fakes, repositories against Ktor's `MockEngine`.
- `companion/src/jvmTest`: Compose UI tests of the whole `App()` on the desktop JVM against a fake server (sign-in,
  sign-out, a server too new, the demo), and the translations (`LocalizationTest`).
- `RealServerJourneyTest` (jvmTest) drives the whole app against the real server: ostrom-server's `app-journey`
  workflow starts a published server image, prepares it with `scripts/real-server-session.sh` (two pushes to main
  waiting for the person, a budget passed, the phone's session and unlock key) and runs this test with that session in
  `OSTROM_E2E_*`; it says yes and no, sees their receipts verified in the history, reads the spending, the activity
  and the account, and the workflow checks the gates and their signed receipts. Without `OSTROM_E2E_SERVER` it is skipped.
- GitHub link: `RemoteGitHubLinkRepositoryTest` (routes, states, each poll outcome, refusals by key), `GitHubLinkUseCasesTest`,
  `GitHubLinkViewModelTest` (polling pending, linked, expired, denied, unreachable, the interval bounds, cancel, unlink),
  `GitHubLinkScreenTest` (jvmTest: the row and its gate, the states, the code and the opened page, refusals, the demo),
  and the GitHub access of a cloud session in `CloudSessionUseCasesTest`, `RemoteCloudSessionRepositoryTest`,
  `CloudSessionGitHubAccessTest` and `CloudSessionsScreenTest`.
- `companion-desktop/src/test`: the CLI's account against a temporary CLI folder and a fake secret store, finding and
  running the CLI with fake processes, the window placement.
- `testAndroidHostTest` runs the common tests on Android's host JVM. Kover's gate covers the domain.

## Parity

What the React Native app did, what TeamCode's companion does that an Ostrom server serves, and where each stands.
"Feature" is the capability key that gates it.

| Area | Feature | Status |
| --- | --- | --- |
| Sign-in (phone browser, desktop device flow), accounts, organizations, secure storage, capabilities check, incompatible server, settings with versions, theme, English and French, demo | none | Done |
| Approvals inbox and request, yes after a biometric unlock bound to the request (`POST /auth/mobile/unlock` with `requestId`) | `gates.v1` | Ported (see [Approvals](#approvals)) |
| Waiting for you (waits asked of the person, answer with actions and values) | `waits.v1` | Ported from TeamCode's `WaitsScreen` (open, answered and cancelled filters, in-progress timers and webhooks, tab badge). Follows the shared feed (see [Live updates](#live-updates)); no withdraw (no route) |
| History: receipts, verified on the device | `receipts.v1` | Ported (see [History](#history)): each receipt verified on the device with ostrom-core's verifier, the server's key pinned on first view, check runs with their origin, paged, demo receipts signed by a demo key |
| Spending | `budgets.v1` | Ported (week and month, budget bar, budgets passed, by agent, repository and person) |
| Activity | none | Ported (newest first, paged; a line about an approval opens its request on a server with `gates.v1`) |
| Notification preferences (requests, decisions, spending, quiet hours) | `notifications.v1` | Ported: Settings, Notifications, shaped like TeamCode's `NotificationPreferencesScreen` |
| Notification switches per channel and kind (`waiting`, `turn_completed`, `turn_failed`, `session_failed`) | `notifications.v1` | Ported from TeamCode's `NotificationPreferencesScreen`: one section per channel the server offers (`GET /notifications/settings`), a switch saved at once (`PUT`) and undone if refused, on the same Notifications screen above the requests and budgets |
| Send a test to my phone, each phone's result | `notifications.push.test.v1` | Ported from TeamCode: `POST /notifications/push/test`; `push_off` and `push_no_device` say what to do, `push_test_rate_limited` when to try again |
| Push registration, encrypted pushes, a tap opens the request | `notifications.push.v1` | Ported (see [Push](#push)) on Android and iOS (the notification service extension replaces the generic alert). A Firebase project for the published apps, and the APNs key in it, are still to create |
| Artifacts gallery and viewer | `artifacts.v1` | Ported from TeamCode's `ArtifactsScreen` and `ArtifactScreen`: the gallery (newest first, paged, searched in the loaded titles), the viewer with its versions, the page in a sandboxed web view on iOS and Android from a reading ticket on the server's content origin (`/a/<id>/<ticket>/`), the console page in the browser on the desktop. No Mine/Team filter and no pasted link (the list route has no scope). An `ostrom://open?url=<console>/artifacts/<id>[?v=<n>]` link opens the artifact (TC-COM-29, see [Artifact links](#artifact-links)) |
| Sessions: list (all, open, ended; keyset-paged; repository, agent, model, person, times, cost, tools) and record (gate answers, its receipt and each answer's opened and verified on the device, its workflow run) | `sessions.v1` | Ported from TeamCode's `SessionsScreen` and `SessionsViewModel`. A run and its session (and a child run and its parent) open each other without stacking: a screen already open below is returned to, what was above it dropped, so back leaves them (TeamCode's session has no link to its run, so it never meets the loop). No transcript, no actions, no new session, no files, no permissions: Ostrom does not serve them to the phone |
| A session's todo: items, state, proof, added along the way, dropped with why, the Stop hook's errors | `todos.v1` | Ported from TeamCode's `TodoPanel` and `FollowTodoUseCase`, read only (no shortcut messages: no session actions) |
| Workflows: the member's runs with their status filter (all, running, waiting, done, failed, cancelled), the workflows (inputs, nodes), a workflow's runs with the same filter, a run's visits; start a run of an accepted workflow with its inputs, cancel a run that has not ended; the editor: new workflow, edit (name, description, domain, session mode, inputs, agent, question and sub-workflow steps, where each outcome goes, visits, limits), save over the version read, delete | `workflows.v1` | Ported from TeamCode's `WorkflowsScreen`, `WorkflowScreen`, `WorkflowStartScreen`, `WorkflowRunScreen` and their view models. Start and Cancel show for an owner or an admin (the role from `GET /me/organizations`; when it cannot be read they show and a refusal is worded by its key: `forbidden`, `workflow_not_accepted`, `run_limit`, `invalid_request`, `run_ended`, `run_busy`). Cancel asks first. No profile picker and no idempotency key on start (Ostrom's route takes neither). The editor is ported from TeamCode's `WorkflowEditScreen`, `WorkflowNodeEditor`, `WorkflowProblemWords` and `WorkflowEditorsCard`: the server's save checks (`WorkflowChecks`, `NodeChecks`, `CallChecks`) run as the person types and are worded against their step; `invalid_workflow` from the server is one worded sentence (its problems are free text). A save reads the stored workflow and changes only what the editor edits, so a gate's approvers, a wait's fields, a check's condition and positions are kept; trigger, step, check, gate and notify steps are named and routed only (the console edits what they do); TeamCode's flow map, field editor, assignee picker, agent profiles and on-device drafts are not ported. New, Edit and Delete show when `GET /workflows/settings` says the person may decide (when it cannot be read they show, and a refusal is worded by its key: `workflow_editors_only`, `workflow_conflict` with Reload, `workflow_name_taken`, `conflict`, `workflow_limit`, `confirmation_limit`, `invalid_workflow`, `workflow_in_use`). Delete asks first. A write held for a confirmation (`202`) says so and opens its console page only when it is on the account's server. Who may edit shows read only to an owner or an admin, with "Open in the console" (the rule is changed from a console session only). TeamCode's filter has no Cancelled chip (its All is the only place cancelled runs show); the app adds one (`status=cancelled`), and the chips scroll sideways on a narrow screen |
| The member's environment: the variable names their agents receive and those withheld (`GET /environment`, values dropped by ostrom-core's client), their personal variants (`GET /me/environment/variants`) | `environment.v1` | Settings, Environment variables, read only. A value is written in the console ("Open in the console", `/me/environment`): the server takes environment writes from a browser session only. TeamCode's companion has no such screen |
| The member's personal MCP servers: a remote server's address, a hosted command's launcher and package, the secret names, the process state | `mcp.personal.v1` | Settings, MCP servers, read only like TeamCode's `McpServersScreen`; added, edited and deleted in the console (`/me/mcp`), whose writes need a browser session |
| Cloud sessions: list, record (status, repository or recipe, times, limits, the preparation step, why it failed), pause, resume, stop, new cloud session with its recipe; a conversation session's transcript, messages, interrupt, permission cards, Bypass, model, effort, background tasks and subagent feeds | `runners.cloud.v1`, `runners.cloud.environment.v1`, `runners.cloud.transfer.v1`, `runners.cloud.conversation.v1`, `runners.cloud.permissions.v1`, `runners.cloud.permissions.bypass.v1`, `runners.cloud.model.v1`, `runners.cloud.effort.v1`, `runners.cloud.tasks.v1`, `runners.cloud.transcript.feeds.v1`, `runners.cloud.files.v1` | Ported from TeamCode's `SessionsScreen`, `SessionScreen`, `NewSessionSheet`, `PermissionCard`, the transcript views and their view models (see [Cloud sessions](#cloud-sessions)). Files (`runners.cloud.files.v1`): TeamCode's paperclip, its chips and pickers; no terminal (a WebSocket the app does not open) |
| GitHub link: the member's link (not linked, linked, reconnect), linking with the device flow, unlinking; a cloud session's GitHub access (mine, shared, none) | `github.link.v1`, `runners.cloud.github.v1` | Ported from TeamCode's `GitHubLinkScreen` and view model (see [GitHub link](#github-link)); the access switch on a cloud session's record is Ostrom's own (TeamCode has none). Not in the app: other members' links (owners and admins) |
| Automations, team MCP servers, sharing | `automations.v1`, `mcp.v1`, `sharing.v1` | Later, each with its client routes in ostrom-core |
| Desktop: menu bar icon, the CLI's account, update banner | none | Ported (see [Desktop](#desktop)). Left: tray notifications of new waits and approvals while the window is hidden (no use case emits them across accounts yet), making an app sign-in the CLI's (it would write the CLI's secret store), the CLI's account on Windows, an install button when no CLI is found, the app's own update (no desktop release) |
| Store builds (TestFlight, Play internal track) | none | Ported: TeamCode's fastlane lanes as is ([distribution.md](distribution.md)) |
| Desktop distribution (signed installers) | none | Ported: the `release-desktop.yml` workflow builds DMG, MSI, DEB and RPM (1.0.0 or later), signed and notarized with a Developer ID once the secrets exist ([distribution.md](distribution.md)); unsigned without them |
