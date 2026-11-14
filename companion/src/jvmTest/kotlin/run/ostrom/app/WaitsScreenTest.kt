// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion jvmTest WaitsScreenTest.kt (Apache-2.0); see NOTICE.
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.usecases.push.NotificationLinks
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import run.ostrom.app.presentation.navigation.IncomingLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Waiting tab of a signed-in account: open waits answered in place, the filters, what is in progress, failures, the demo. */
@OptIn(ExperimentalTestApi::class)
class WaitsScreenTest {
    private val origin = "https://ostrom.example.org"

    private fun wait(id: String, title: String, status: String = "open", extra: String = "", fields: String = "", actions: String = """{"id":"done","label":"Send","style":"primary"}""") = """
        {"id":"$id","kind":"question","status":"$status","assignee":"ada","createdAt":"2026-11-13T10:00:00Z",
         "question":{"title":"$title","fields":[$fields],"actions":[$actions]}$extra}
    """.trimIndent()

    private val store = """{"id":"store","type":"choice","label":"Store","required":true,"options":["App Store","Google Play"]}"""

    private fun server(features: String = "\"waits.v1\"") = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":[$features]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/waits?status=open&resolver=human" to """{"items":[${wait("w1", "Which store do we ship first?", fields = store)}]}""",
            "GET /api/v1/waits?status=open&resolver=time" to """{"items":[${wait("t1", "Check the nightly deploy", extra = ""","resolver":"time","dueAt":"2026-11-13T12:00:00Z"""")}]}""",
            "GET /api/v1/waits?status=open&resolver=event" to """{"items":[]}""",
            "GET /api/v1/waits?status=resolved" to """{"items":[${wait("w0", "Is the tone right?", status = "resolved", fields = """{"id":"feedback","type":"text","label":"Feedback"}""",
                extra = ""","resolvedAt":"2026-11-13T10:05:00Z","answer":{"action":"done","values":{"feedback":"Keep it short."},"author":"ada","answeredAt":"2026-11-13T10:05:00Z"}""")}]}""",
            "POST /api/v1/waits/w1/answer" to wait("w1", "Which store do we ship first?", status = "resolved", fields = store),
        ),
    )

    /** A device with one signed-in account on [server]. */
    private fun signedIn(server: FakeServer, member: Boolean = false, block: DesktopComposeUiTest.() -> Unit) {
        val settings = MapSettings()
        val account = Account(id = "a1", origin = origin, login = "ada", method = SignInMethod.DEVICE)
        // The organization and member a push names.
        SettingsAccountRepository(settings).save(if (member) account.copy(organizationId = "o1", memberId = "m1") else account)
        val credentials = MemoryCredentialStore().also { runBlocking { it.write("a1", "osm_token") } }
        testApp(server, credentials = credentials, settings = settings) { block() }
    }

    private fun DesktopComposeUiTest.waitFor(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aTappedNotificationAboutASessionOrAWaitOpensTheWaits() {
        val server = server(features = "\"gates.v1\",\"waits.v1\"")
        server.routes["GET /api/v1/approvals/queue"] = """{"operator":"ada","you":{"login":"ada","via":"app"},"pending":[],"recent":[]}"""
        signedIn(server, member = true) {
            // The inbox is the first tab of a server with gates.
            waitFor("tab-inbox")
            settle()
            onNodeWithTag("wait-w1").assertDoesNotExist()
            // The agent of a session waits for the member: the app has no session screen yet, the waits show it.
            IncomingLinks.open(NotificationLinks.link("$origin/", "o1", "m1", null, sessionId = "s1", waitId = "w1"))
            waitFor("wait-w1")
            assertEquals(null, IncomingLinks.link.value)
        }
    }

    @Test
    fun anOpenWaitIsAnsweredInPlaceAndAMissingFieldIsSaidFirst() {
        val server = server()
        signedIn(server) {
            waitFor("wait-w1")
            onNodeWithText("Which store do we ship first?").assertExists()
            onNodeWithTag("tab-badge-waits", useUnmergedTree = true).assertExists()
            // In progress, below what waits for the person: a timer, nothing to answer.
            onNodeWithTag("in-progress-t1").performScrollTo()
            onNodeWithText("Check the nightly deploy").assertExists()

            onNodeWithTag("wait-action-done").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("wait-problem").assertExists()
            onNodeWithText("Store is required.").assertExists()
            assertTrue("POST /api/v1/waits/w1/answer" !in server.asked, "nothing is sent before the field is filled")

            onNodeWithTag("wait-option-store-Google Play").performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithTag("wait-action-done").performSemanticsAction(SemanticsActions.OnClick)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("wait-w1")).fetchSemanticsNodes().isEmpty() }
            val body = server.bodies.getValue("POST /api/v1/waits/w1/answer")
            assertTrue("\"action\":\"done\"" in body && "\"store\":\"Google Play\"" in body, body)
            onNodeWithTag("waits-empty").assertExists()
        }
    }

    @Test
    fun theAnsweredFilterShowsWhatWasGiven() {
        signedIn(server()) {
            waitFor("wait-w1")
            onNodeWithTag("waits-filter-answered").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("wait-w0")
            onNodeWithTag("wait-status-w0", useUnmergedTree = true).assertExists()
            onNodeWithText("Feedback: Keep it short.").assertExists()
            onNodeWithText("Answer: Send").assertExists()
        }
    }

    @Test
    fun aWaitAnsweredElsewhereLeavesWithANotice() {
        val server = server().also { it.failures["POST /api/v1/waits/w1/answer"] = 409 to "wait_closed" }
        signedIn(server) {
            waitFor("wait-w1")
            onNodeWithTag("wait-option-store-App Store").performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithTag("wait-action-done").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("waits-closed")
            onNodeWithTag("wait-w1").assertDoesNotExist()
        }
    }

    @Test
    fun aFailedLoadOffersRetry() {
        val server = server().also { it.failures["GET /api/v1/waits"] = 503 to "unavailable" }
        signedIn(server) {
            waitFor("waits-error-state")
            server.failures.clear()
            onNodeWithTag("waits-retry").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("wait-w1")
        }
    }

    @Test
    fun aServerWithoutWaitsHasNoWaitingTab() {
        val server = server(features = "")
        signedIn(server) {
            // Activity is the first tab of a server without waits.
            waitFor("activity")
            onNodeWithTag("tab-waiting").assertDoesNotExist()
        }
        assertTrue(server.asked.none { it.startsWith("GET /api/v1/waits") }, server.asked.toString())
    }

    @Test
    fun theDemoAnswersAWaitFromMemory() {
        val server = server()
        testApp(server) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            // The demo serves the approvals too, whose inbox is the first tab.
            waitFor("tab-waiting")
            onNodeWithTag("tab-waiting").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            waitFor("wait-w3")
            onNodeWithTag("wait-option-store-App Store").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            onNodeWithTag("wait-action-done").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("wait-w3")).fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("waits-filter-answered").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("wait-w3")
            onNodeWithText("Store: App Store").assertExists()
        }
        assertTrue(server.clients == 0, "the demo makes no request")
    }
}
