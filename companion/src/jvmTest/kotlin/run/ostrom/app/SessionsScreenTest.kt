// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion jvmTest SessionsScreenTest.kt (Apache-2.0); see NOTICE.
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import run.ostrom.app.demo.DemoSessionIds
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.usecases.push.NotificationLinks
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import run.ostrom.app.presentation.navigation.IncomingLinks
import run.ostrom.core.receipts.ReceiptSignerImpl
import run.ostrom.core.receipts.ReceiptSigningKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Sessions tab and a session's record on the desktop target: paged, filtered, gate answers, todo, links, the demo. */
@OptIn(ExperimentalTestApi::class)
class SessionsScreenTest {
    private val origin = "https://ostrom.example.org"

    private fun session(id: String, repo: String, ended: Boolean = false, extra: String = "") =
        """{"id":"$id","realRepo":"$repo","agent":"claude-code","model":"claude-opus","dev":"ada","startedAt":"2026-11-14T09:00:00Z",""" +
            (if (ended) """"endedAt":"2026-11-14T10:00:00Z","endedBy":"SessionEnd hook",""" else "") +
            """"tools":12,"costUsd":1.5$extra}"""

    private val todo = """{"todo":{"id":"t1","sessionId":"s1","reference":"Ship the billing plan","status":"active","items":[
        {"id":"a","name":"Write the tests","description":"","doneWhen":{"text":"CI green","checks":[]},"origin":"plan","status":"done",
         "proof":{"at":"2026-11-14T09:10:00Z","passed":true,"text":"CI passed","urls":[]}},
        {"id":"b","name":"Document it","description":"","doneWhen":{"text":"page published","checks":[]},"origin":"discovered","originReason":"Asked in review","status":"doing"},
        {"id":"c","name":"Benchmark","description":"","doneWhen":{"text":"numbers","checks":[]},"origin":"plan","status":"dropped","dropReason":"Not needed"}],
        "createdAt":"2026-11-14T09:00:00Z","updatedAt":"2026-11-14T09:20:00Z"}}"""

    private val key = ReceiptSigningKey.generate()

    /** A receipt of [id] signed by the server's key, as `GET /api/v1/receipts/{id}` answers it. */
    private fun receipt(id: String) = ReceiptSignerImpl(key).sign(buildJsonObject {
        put("id", id)
        putJsonObject("subject") { put("kind", "gate"); put("id", "subject-$id"); put("digest", "ab".repeat(32)) }
        put("initiator", "charles")
        put("rules", buildJsonArray {})
        putJsonArray("decisions") { add(buildJsonObject { put("approvalId", "g-$id"); put("by", "carol"); put("at", "2026-11-14T09:30:00Z"); put("result", "approved") }) }
        put("createdAt", "2026-11-14T09:00:00.000Z")
    }).toString()

    private fun keys() = buildJsonObject {
        putJsonArray("keys") { add(buildJsonObject { put("kid", key.publicKey.kid); put("alg", "Ed25519"); put("publicKeyPem", key.publicKey.toSpkiPem()) }) }
    }.toString()

    private fun server(features: String = "\"sessions.v1\",\"todos.v1\",\"receipts.v1\",\"workflows.v1\"") = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":[$features]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            "GET /api/v1/receipts" to """{"items":[]}""",
            "GET /api/v1/receipts/keys" to keys(),
            "GET /api/v1/receipts/rc1" to receipt("rc1"),
            "GET /api/v1/receipts/rc-g1" to receipt("rc-g1"),
            "GET /api/v1/sessions?before=c2" to """{"items":[${session("s1", "acme/api")},${session("s2", "acme/web", ended = true)}]}""",
            "GET /api/v1/sessions?open=false" to """{"items":[${session("s2", "acme/web", ended = true)}]}""",
            "GET /api/v1/sessions" to """{"items":[${session("s1", "acme/api")}],"next":"c2"}""",
            "GET /api/v1/sessions/s2" to """{"session":${session("s2", "acme/web", ended = true,
                ""","receiptId":"rc1","runId":"run1","exitCode":0,"gateAnswers":[{"gateId":"g1","actionClass":"git.push","outcome":"approved","by":"carol","at":"2026-11-14T09:30:00Z","receiptId":"rc-g1"}]""")}}""",
            "GET /api/v1/sessions/s1" to """{"session":${session("s1", "acme/api")}}""",
            "GET /api/v1/todos/session" to todo,
            "GET /api/v1/runs/run1" to """{"id":"run1","workflowId":"wf1","workflowName":"Release","workflowVersion":3,"status":"succeeded","startedBy":"ada",
                "source":"manual","visits":[],"startedAt":"2026-11-14T09:00:00Z","endedAt":"2026-11-14T09:30:00Z"}""",
        ),
    )

    /** A device with one signed-in account on [server]; a [member] one names the organization and member a push names. */
    private fun signedIn(server: FakeServer, member: Boolean = false, block: DesktopComposeUiTest.() -> Unit) {
        val settings = MapSettings()
        val account = Account(id = "a1", origin = origin, login = "ada", method = SignInMethod.DEVICE)
        SettingsAccountRepository(settings).save(if (member) account.copy(organizationId = "o1", memberId = "m1") else account)
        val credentials = MemoryCredentialStore().also { runBlocking { it.write("a1", "osm_token") } }
        testApp(server, credentials = credentials, settings = settings) { block() }
    }

    private fun DesktopComposeUiTest.waitFor(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.waitForText(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.click(tag: String) {
        waitFor(tag)
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun aTappedNotificationAboutASessionOpensItsRecord() {
        signedIn(server(), member = true) {
            waitFor("tab-sessions")
            settle()
            onNodeWithTag("session-overview").assertDoesNotExist()
            // The agent of session s1 waits for the member: the server serves sessions, the session's record opens.
            IncomingLinks.open(NotificationLinks.link("$origin/", "o1", "m1", null, sessionId = "s1", waitId = "w1"))
            waitFor("session-overview")
            assertEquals(null, IncomingLinks.link.value)
            // Back leaves it for the tabs.
            click("back")
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("session-overview")).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test
    fun theListReadsItsOlderPagesAndItsFilters() {
        val server = server()
        signedIn(server) {
            click("tab-sessions")
            waitFor("session-s1")
            // The end of a short page is on screen: the older page loads by itself, the first row not repeated.
            waitFor("session-s2")
            assertEquals(1, onAllNodes(hasTestTag("session-s1")).fetchSemanticsNodes().size)
            waitForText("acme/web")
            waitForText("claude-code · claude-opus")
            waitForText("12 tools")
            onNodeWithTag("session-state-s1", useUnmergedTree = true).assertExists()
            click("sessions-filter-ended")
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("session-s1")).fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("session-s2").assertExists()
            assertTrue("GET /api/v1/events" !in server.asked, "No feed asked of a server that does not announce it")
        }
    }

    @Test
    fun aRecordShowsItsGateAnswersItsTodoAndOpensItsRunAndItsReceipt() {
        val server = server()
        signedIn(server) {
            click("tab-sessions")
            click("session-s2")
            waitFor("session-overview")
            waitForText("Ended by SessionEnd hook")
            waitFor("session-gate-g1")
            waitForText("git.push")
            waitForText("Receipt rc1")
            onNodeWithTag("todo-none").assertDoesNotExist()
            // The record's run opens, then back to the record.
            click("session-open-run")
            waitFor("workflow-run-overview")
            waitForText("Release, version 3")
            click("back")
            // The receipt itself opens, verified on this device, not the History tab; back returns to the record.
            click("session-open-receipt")
            waitFor("receipt-rc1")
            waitFor("check-verified")
            waitForText("Signature verified on this device · key ${key.publicKey.kid}")
            waitForText("gate subject-rc1")
            onNodeWithTag("history").assertDoesNotExist()
            assertTrue("GET /api/v1/receipts/rc1" in server.asked)
            assertTrue("GET /api/v1/receipts" !in server.asked, "the receipt is read by its id, not found in the list")
            click("back")
            waitFor("session-overview")
            // A gate answer opens its own receipt.
            click("session-gate-g1-receipt")
            waitFor("receipt-rc-g1")
            waitFor("check-verified")
        }
    }

    @Test
    fun aReceiptTheServerDoesNotHaveSaysSoAndATamperedOneIsRejected() {
        val server = server()
        server.routes.remove("GET /api/v1/receipts/rc1")
        server.routes["GET /api/v1/receipts/rc-g1"] = receipt("rc-g1").replace("charles", "mallory")
        signedIn(server) {
            click("tab-sessions")
            click("session-s2")
            click("session-open-receipt")
            waitFor("receipt-error")
            waitForText("This no longer exists")
            onNodeWithTag("receipt-retry").assertDoesNotExist()
            click("back")
            click("session-gate-g1-receipt")
            waitFor("check-rejected")
        }
    }

    @Test
    fun theTodoPanelShowsItsItemsProofsAndWhatWasAddedOrDropped() {
        val server = server()
        signedIn(server) {
            click("tab-sessions")
            click("session-s1")
            waitFor("todo-reference")
            waitForText("Ship the billing plan")
            onNodeWithTag("todo-counter").assertExists()
            waitForText("1 / 2")
            waitFor("todo-proof-a")
            waitFor("todo-added-b")
            waitForText("Added because: Asked in review")
            waitFor("todo-drop-c")
            waitForText("Now: Document it")
            assertTrue(server.asked.count { it == "GET /api/v1/todos/session" } >= 1)
        }
    }

    @Test
    fun aServerWithoutTodosHasNoPanelAndOneWithoutSessionsNoTab() {
        signedIn(server("\"sessions.v1\"")) {
            click("tab-sessions")
            click("session-s2")
            waitFor("session-overview")
            onNodeWithTag("todo-view").assertDoesNotExist()
            onNodeWithTag("session-open-receipt").assertDoesNotExist()
            onNodeWithTag("session-gate-g1-receipt").assertDoesNotExist()
            onNodeWithTag("session-open-run").assertDoesNotExist()
        }
        signedIn(server("\"gates.v1\"")) {
            waitFor("tab-settings")
            onNodeWithTag("tab-sessions").assertDoesNotExist()
            onNodeWithTag("tab-workflows").assertDoesNotExist()
        }
    }

    @Test
    fun theDemoShowsItsSessionsAndTodoFromMemory() {
        val server = server()
        testApp(server) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            click("tab-sessions")
            waitFor("session-${DemoSessionIds.OPEN}")
            onNodeWithTag("session-${DemoSessionIds.ENDED}").assertExists()
            onNodeWithTag("session-${DemoSessionIds.LOST}").assertExists()
            click("session-${DemoSessionIds.OPEN}")
            waitFor("todo-reference")
            waitFor("todo-item-docs")
            waitFor("todo-hook-errors")
            // The ended session's receipt is one of the demo's, verified with the demo's key.
            click("back")
            click("session-${DemoSessionIds.ENDED}")
            click("session-open-receipt")
            waitFor("receipt-rcpt_demo-release")
            waitFor("check-verified")
        }
        assertTrue(server.clients == 0, "the demo makes no request")
    }
}
