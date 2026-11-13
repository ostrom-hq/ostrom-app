// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.services.BiometricOutcome
import run.ostrom.app.domain.services.unlockKeyEntry
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The approvals inbox and a request, the whole app on the desktop JVM against a fake server, and in the demo. */
@OptIn(ExperimentalTestApi::class)
class ApprovalsFlowTest {
    private val origin = "https://ostrom.example.org"
    private val queue = "GET /api/v1/approvals/queue"
    private val view = "GET /api/v1/approvals/appr_0001/view"
    private val answer = "POST /api/v1/approvals/appr_0001/answer"
    private val unlock = "POST /api/v1/auth/mobile/unlock"

    private fun approval(id: String = "appr_0001", status: String = "pending", digest: String = "sha256:shown", extra: String = "") =
        """{"id":"$id","title":"Push to main","requestedFrom":"ada","channel":"app","status":"$status","shownDigest":"$digest",
           "repo":"acme/api","askedBy":["grace"],"commitDetails":[{"sha":"c0ffee0123","title":"Fix the login","author":"grace"}],
           "checks":[{"name":"ci","state":"success"}],"action":{"kind":"push","repo":"acme/api","ref":"main","headSha":"c0ffee0123","risk":"high"},
           "diffStats":{"files":3,"additions":40,"deletions":2}$extra}"""

    private fun viewOf(approval: String, canApprove: Boolean = true, self: Boolean = false) =
        """{"approval":$approval,"who":{"dev":"grace","agent":"claude-code","model":"opus"},"what":{"command":"git push origin main"},
           "gate":{"workflowId":"w1","workflowName":"Release","nodeId":"n1","label":"Push to main","approvers":["ada"],"status":"accepted","enforce":true,"reason":"r"},
           "you":{"login":"ada","self":$self,"canApprove":$canApprove}}"""

    private fun server(pending: String = viewOf(approval())) = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":["gates.v1"]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"app"},"capabilities":[]}""",
            queue to """{"operator":"ada","you":{"login":"ada","via":"app"},"pending":[$pending],
                "recent":[${viewOf(approval("appr_0000", status = "approved", extra = ""","decidedBy":"ada""""))}]}""",
            view to pending,
            unlock to """{"authenticatedAt":"2026-11-13T10:00:00Z"}""",
            answer to viewOf(approval(status = "approved", extra = ""","decidedBy":"ada"""")),
        ),
    )

    /** A phone's account (an unlock key kept at sign-in) or a computer's (none). */
    private fun signedIn(phone: Boolean): Pair<MapSettings, MemoryCredentialStore> {
        val settings = MapSettings()
        val method = if (phone) SignInMethod.BROWSER else SignInMethod.DEVICE
        SettingsAccountRepository(settings).save(Account(id = "a1", origin = origin, login = "ada", organizationId = "o1", method = method))
        val credentials = MemoryCredentialStore()
        runBlocking {
            credentials.write("a1", "osm_token")
            if (phone) credentials.write(unlockKeyEntry("a1"), "key-1")
        }
        return settings to credentials
    }

    private fun DesktopComposeUiTest.waitFor(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    /** Taps [tag], scrolled into view first when it sits in a scrolling list ([scroll]). */
    private fun DesktopComposeUiTest.tap(tag: String, scroll: Boolean = true) {
        val node = onNodeWithTag(tag)
        (if (scroll) node.performScrollTo() else node).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun DesktopComposeUiTest.openRequest() {
        waitFor("request-appr_0001")
        tap("request-appr_0001")
        waitFor("request-actions")
    }

    @Test
    fun theInboxListsWhatWaitsThenWhatWasAnsweredAndARowOnlyOpens() {
        val server = server()
        val (settings, credentials) = signedIn(phone = true)
        testApp(server, settings = settings, credentials = credentials) {
            waitFor("request-appr_0001")
            onNodeWithText("Waiting for your answer", substring = true).assertExists()
            onNodeWithText("Answered lately", substring = true).assertExists()
            onNodeWithTag("request-appr_0000").assertExists()
            openRequest()
            // Everything before any button: who asks, through which gate, the command, the commits and checks.
            onNodeWithText("claude-code · opus", substring = true).assertExists()
            onNodeWithText("Release · Push to main", substring = true).assertExists()
            onNodeWithText("git push origin main", substring = true).assertExists()
            onNodeWithText("Fix the login", substring = true).assertExists()
            onNodeWithText("Files: 3 · +40 −2", substring = true).assertExists()
            onNodeWithText("Approve push", substring = true).assertExists()
            assertFalse(answer in server.asked, "Opening a request answers nothing")
            assertFalse(unlock in server.asked)
        }
    }

    @Test
    fun aPhonesYesUnlocksForItsRequestThenIsBoundToTheDigestRead() {
        val server = server()
        val gate = FakeBiometricGate()
        val (settings, credentials) = signedIn(phone = true)
        testApp(server, settings = settings, credentials = credentials, biometrics = gate) {
            openRequest()
            tap("request-approve")
            waitUntil(timeoutMillis = 10_000) { answer in server.asked }
            assertEquals(listOf("Approve “Push to main”"), gate.prompts)
            val unlocked = server.sent.getValue(unlock).single()
            assertTrue("\"unlockKey\":\"key-1\"" in unlocked && "\"requestId\":\"appr_0001\"" in unlocked, unlocked)
            assertTrue(server.asked.indexOf(unlock) < server.asked.indexOf(answer), "The unlock goes before the answer")
            assertTrue("\"shownDigest\":\"sha256:shown\"" in server.sent.getValue(answer).single())
            waitFor("request-outcome")
            onNodeWithTag("request-actions").assertDoesNotExist()
        }
    }

    @Test
    fun aCancelledPromptSendsNothing() {
        val server = server()
        val gate = FakeBiometricGate(BiometricOutcome.CANCELLED)
        val (settings, credentials) = signedIn(phone = true)
        testApp(server, settings = settings, credentials = credentials, biometrics = gate) {
            openRequest()
            tap("request-approve")
            assertEquals(1, gate.prompts.size)
            assertFalse(unlock in server.asked)
            assertFalse(answer in server.asked)
            onNodeWithTag("request-problem").assertDoesNotExist()
        }
    }

    @Test
    fun aRequestThatChangedIsReadAgainBeforeAnyAnswer() {
        val server = server()
        server.routes[answer] = """{"error":{"type":"shown_digest_mismatch","message":"m"}}"""
        server.statuses[answer] = 409
        val (settings, credentials) = signedIn(phone = false)
        testApp(server, settings = settings, credentials = credentials) {
            openRequest()
            // A push landed: another commit, another digest.
            server.routes[view] = viewOf(approval(digest = "sha256:after-push"))
            tap("request-approve")
            waitFor("request-reread")
            onNodeWithText("The request changed after you opened it", substring = true).assertExists()
            onNodeWithTag("request-approve").assertDoesNotExist()
            tap("request-reread")
            server.routes[answer] = viewOf(approval(status = "approved", digest = "sha256:after-push"))
            server.statuses.remove(answer)
            tap("request-approve")
            waitUntil(timeoutMillis = 10_000) { server.sent.getValue(answer).size == 2 }
            assertTrue("\"shownDigest\":\"sha256:after-push\"" in server.sent.getValue(answer).last())
        }
    }

    @Test
    fun aYesTheServerWantsConfirmedOpensTheConsoleOrShowsItsAddress() {
        val server = server()
        server.routes[answer] = """{"error":{"type":"step_up_required","message":"Sign in again"}}"""
        server.statuses[answer] = 403
        val links = RecordingUriHandler()
        val (settings, credentials) = signedIn(phone = false)
        testApp(server, settings = settings, credentials = credentials, links = links) {
            openRequest()
            // A computer holds no unlock key: its yes goes with its token, and the server decides.
            tap("request-approve")
            waitFor("request-open-console")
            assertFalse(unlock in server.asked)
            onNodeWithText("confirm it in the console", substring = true).assertExists()
            onNodeWithText("Sign in again", substring = true).assertDoesNotExist()
            tap("request-open-console")
            assertEquals(listOf("$origin/yes/appr_0001"), links.opened)
            links.fails = true
            tap("request-open-console")
            waitFor("request-console-url")
            onNodeWithText("$origin/yes/appr_0001").assertExists()
        }
    }

    @Test
    fun anUnlockTooOldForThisYesAsksToSayYesAgain() {
        val server = server()
        server.routes[answer] = """{"error":{"type":"step_up_required","message":"m","requestBound":true}}"""
        server.statuses[answer] = 403
        val (settings, credentials) = signedIn(phone = true)
        testApp(server, settings = settings, credentials = credentials, biometrics = FakeBiometricGate()) {
            openRequest()
            tap("request-approve")
            waitFor("request-problem")
            onNodeWithText("say yes again", substring = true).assertExists()
            onNodeWithTag("request-open-console").assertDoesNotExist()
        }
    }

    @Test
    fun noYesOnATruncatedChangeAndANoNeedsItsReason() {
        val server = server(pending = viewOf(approval(extra = ""","diffTruncated":true""")))
        val (settings, credentials) = signedIn(phone = true)
        val gate = FakeBiometricGate()
        testApp(server, settings = settings, credentials = credentials, biometrics = gate) {
            openRequest()
            onNodeWithTag("request-approve").assertDoesNotExist()
            onNodeWithText("approve it from the console", substring = true).assertExists()
            tap("request-decline")
            tap("request-send-decline")
            assertFalse(answer in server.asked, "No no without its reason")
            onNodeWithTag("request-reason").performTextInput("Split it in two")
            tap("request-send-decline")
            waitUntil(timeoutMillis = 10_000) { answer in server.asked }
            val sent = server.sent.getValue(answer).single()
            assertTrue("\"answer\":\"reject\"" in sent && "\"note\":\"Split it in two\"" in sent, sent)
            assertEquals(listOf("Decline “Push to main”"), gate.prompts)
        }
    }

    @Test
    fun aYesTheServerDoesNotTakeIsSaidInThePersonsLanguage() {
        val server = server(pending = viewOf(approval(), canApprove = false, self = true).replace("\"canApprove\":false", "\"canApprove\":false,\"reason\":\"Only a maintainer\""))
        val (settings, credentials) = signedIn(phone = true)
        testApp(server, settings = settings, credentials = credentials, biometrics = FakeBiometricGate()) {
            openRequest()
            onNodeWithTag("request-approve").assertDoesNotExist()
            onNodeWithText("Your yes is not taken on this request.").assertExists()
            onNodeWithText("You asked for this", substring = true).assertExists()
            onNodeWithText("Only a maintainer", substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun aFailedRefreshKeepsTheListAndSaysItMayBeOutOfDate() {
        val server = server()
        val (settings, credentials) = signedIn(phone = true)
        testApp(server, settings = settings, credentials = credentials) {
            waitFor("request-appr_0001")
            server.routes[queue] = """{"error":{"type":"org_required","message":"m"}}"""
            server.statuses[queue] = 409
            tap("inbox-refresh", scroll = false)
            waitFor("inbox-stale")
            onNodeWithText("may be out of date", substring = true).assertExists()
            onNodeWithTag("request-appr_0001").assertExists()
            tap("inbox-choose-organization", scroll = false)
            waitFor("settings")
        }
    }

    @Test
    fun theInboxSaysWhenTheServerWantsTheOrganization() {
        val server = server()
        server.routes[queue] = """{"error":{"type":"org_required","message":"m"}}"""
        server.statuses[queue] = 409
        val (settings, credentials) = signedIn(phone = true)
        testApp(server, settings = settings, credentials = credentials) {
            waitFor("inbox-error")
            onNodeWithText("several organizations", substring = true).assertExists()
            onNodeWithTag("inbox-choose-organization").assertExists()
        }
    }

    @Test
    fun theDemoAnswersFromMemoryWithItsGateAlwaysConfirming() {
        val server = server()
        testApp(server) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            waitFor("request-demo-push")
            onNodeWithTag("request-demo-tool").assertExists()
            onNodeWithTag("request-demo-merge").assertExists()
            tap("request-demo-push")
            waitFor("request-approve")
            tap("request-approve")
            waitFor("request-outcome")
            onNodeWithText("Approved by ada", substring = true).assertExists()
            tap("back", scroll = false)
            waitFor("request-demo-tool")
            onNodeWithTag("request-demo-push").performScrollTo()
            onNodeWithText("Answered lately", substring = true).assertExists()
        }
        assertEquals(0, server.clients, "The demo reaches no server")
    }
}
