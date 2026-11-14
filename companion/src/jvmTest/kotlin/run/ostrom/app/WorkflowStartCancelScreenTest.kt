// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/jvmTest/.../WorkflowsScreenTest.kt; see NOTICE.
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import run.ostrom.app.demo.DemoWorkflowIds
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertTrue

/** Starting a run from a workflow and cancelling a run, on the desktop target: for an owner, not for a member, a refusal worded by its key, the demo. */
@OptIn(ExperimentalTestApi::class)
class WorkflowStartCancelScreenTest {
    private val origin = "https://ostrom.example.org"

    private fun run(id: String, status: String, extra: String = "") = """{"id":"$id","workflowId":"wf1","workflowName":"Release","workflowVersion":3,
        "status":"$status","startedBy":"ada","source":"manual","currentNodeId":"build","currentNodeKind":"step","startedAt":"2026-11-14T09:00:00Z"$extra}"""

    private val definition = """{"id":"wf1","name":"Release","domain":"engineering","version":3,
        "nodes":[{"id":"build","kind":"step","label":"Build"}],"edges":[],
        "inputs":[{"id":"version","label":"Version","required":true,"maxLength":20},{"id":"channel","label":"Channel","default":"stable"}],
        "status":"accepted","foundAt":"2026-11-01T10:00:00Z"}"""

    private fun server(role: String?) = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":["workflows.v1"]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            "GET /api/v1/runs" to """{"items":[]}""",
            "GET /api/v1/workflows" to """{"items":[$definition]}""",
            "GET /api/v1/workflows/wf1" to definition,
            "GET /api/v1/workflows/wf1/runs" to """{"items":[]}""",
            "POST /api/v1/workflows/wf1/runs" to run("r7", "queued", ""","inputs":{"version":"2.0.0","channel":"stable"}"""),
            "GET /api/v1/runs/r7" to run("r7", "running", ""","inputs":{"version":"2.0.0","channel":"stable"}"""),
            "POST /api/v1/runs/r7/cancel" to run("r7", "cancelled", ""","endedAt":"2026-11-14T09:10:00Z""""),
        ).apply {
            if (role != null) put("GET /api/v1/me/organizations", """{"items":[{"id":"o1","slug":"acme","name":"Acme","avatarUrl":"","role":"$role"}]}""")
        },
    )

    private fun signedIn(server: FakeServer, block: DesktopComposeUiTest.() -> Unit) {
        val settings = MapSettings()
        SettingsAccountRepository(settings).save(Account(id = "a1", origin = origin, login = "ada", method = SignInMethod.DEVICE))
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

    private fun DesktopComposeUiTest.openWorkflow() {
        click("tab-workflows")
        click("workflows-part-definitions")
        click("workflow-wf1")
        waitFor("workflow-overview")
    }

    @Test
    fun anOwnerStartsARunWithItsInputsThenCancelsIt() {
        val server = server("owner")
        signedIn(server) {
            openWorkflow()
            click("workflow-start")
            waitFor("workflow-input-version")
            waitForText("Version (required)")
            waitForText("stable")
            onNodeWithTag("workflow-start-submit").assertIsNotEnabled()
            onNodeWithTag("workflow-input-version").performTextInput("2.0.0")
            onNodeWithTag("workflow-start-submit").assertIsEnabled()
            click("workflow-start-submit")
            waitFor("workflow-run-overview")
            waitForText("version: 2.0.0")
            val sent = server.bodies["POST /api/v1/workflows/wf1/runs"].orEmpty()
            assertTrue(""""version":"2.0.0"""" in sent && """"channel":"stable"""" in sent, sent)
            click("workflow-run-cancel")
            waitFor("workflow-cancel-confirm")
            server.routes["GET /api/v1/runs/r7"] = run("r7", "cancelled", ""","endedAt":"2026-11-14T09:10:00Z"""")
            click("workflow-cancel-yes")
            waitUntil(timeoutMillis = 10_000) { "POST /api/v1/runs/r7/cancel" in server.asked }
            waitForText("Cancelled")
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("workflow-run-cancel")).fetchSemanticsNodes().isEmpty() }
            click("back")
            waitFor("workflow-overview")
        }
    }

    @Test
    fun aMemberSeesNeitherStartNorCancel() {
        val server = server("member")
        server.routes["GET /api/v1/workflows/wf1/runs"] = """{"items":[${run("r7", "running")}]}"""
        signedIn(server) {
            openWorkflow()
            waitUntil(timeoutMillis = 10_000) { "GET /api/v1/me/organizations" in server.asked }
            waitFor("workflow-run-r7")
            onNodeWithTag("workflow-start").assertDoesNotExist()
            click("workflow-run-r7")
            waitFor("workflow-run-overview")
            onNodeWithTag("workflow-run-cancel").assertDoesNotExist()
        }
    }

    @Test
    fun whenTheRoleIsUnknownTheServersRefusalIsWordedByItsKey() {
        val server = server(role = null)
        server.failures["POST /api/v1/workflows/wf1/runs"] = 403 to "forbidden"
        signedIn(server) {
            openWorkflow()
            click("workflow-start")
            onNodeWithTag("workflow-input-version").performTextInput("2.0.0")
            click("workflow-start-submit")
            waitFor("workflow-start-error")
            waitForText("Only an owner or an admin of the organization starts and cancels workflow runs.")
        }
    }

    @Test
    fun theDemoStartsAndCancelsARunInMemory() {
        val server = server("owner")
        testApp(server) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            click("tab-workflows")
            click("workflows-part-definitions")
            click("workflow-${DemoWorkflowIds.TRIAGE}")
            waitFor("workflow-overview")
            onNodeWithTag("workflow-start").assertDoesNotExist()
            click("back")
            click("workflow-${DemoWorkflowIds.RELEASE}")
            click("workflow-start")
            onNodeWithTag("workflow-input-version").performTextInput("1.5.0")
            click("workflow-start-submit")
            waitFor("workflow-run-overview")
            waitForText("version: 1.5.0")
            click("workflow-run-cancel")
            click("workflow-cancel-yes")
            waitForText("Cancelled")
        }
        assertTrue(server.clients == 0, "the demo makes no request")
    }
}
