// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/jvmTest/.../WorkflowsScreenTest.kt; see NOTICE.
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
import run.ostrom.app.demo.DemoWorkflowIds
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertTrue

/** The Workflows tab on the desktop target: the member's runs and their filter, the workflows read only, a workflow's runs, a run's visits, the demo. */
@OptIn(ExperimentalTestApi::class)
class WorkflowsScreenTest {
    private val origin = "https://ostrom.example.org"

    private fun run(id: String, status: String, extra: String = "") = """{"id":"$id","workflowId":"wf1","workflowName":"Release","workflowVersion":3,
        "status":"$status","startedBy":"ada","source":"manual","currentNodeId":"ok","currentNodeKind":"gate","startedAt":"2026-11-14T09:00:00Z"$extra}"""

    private val definition = """{"id":"wf1","name":"Release","domain":"engineering","description":"Ship a version","version":3,
        "nodes":[{"id":"build","kind":"step","label":"Build","run":{"command":"./gradlew build"}},{"id":"ok","kind":"gate","label":"Approve the merge"}],
        "edges":[{"from":"build","to":"ok"}],"inputs":[{"id":"version","label":"Version","required":true}],"status":"accepted","foundAt":"2026-11-01T10:00:00Z"}"""

    private fun server() = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":["workflows.v1","sessions.v1"]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            "GET /api/v1/sessions" to """{"items":[]}""",
            "GET /api/v1/runs?status=failed" to """{"items":[${run("r3", "failed", ""","failure":"The build broke"""")}]}""",
            "GET /api/v1/runs" to """{"items":[${run("r1", "waiting")},${run("r2", "succeeded", ""","endedAt":"2026-11-14T09:30:00Z"""")}]}""",
            "GET /api/v1/runs/r1" to run("r1", "waiting", ""","inputs":{"version":"1.4.0"},"sessionId":"s9","visits":[
                {"seq":1,"nodeId":"build","kind":"step","status":"done","outcome":"passed","output":"412 tests passed","nodeTitle":"Build",
                 "startedAt":"2026-11-14T09:00:00Z","endedAt":"2026-11-14T09:05:00Z"},
                {"seq":2,"nodeId":"ok","kind":"gate","status":"active","gateId":"g1","nodeTitle":"Approve the merge","startedAt":"2026-11-14T09:05:00Z"}]"""),
            "GET /api/v1/sessions/s9" to """{"session":{"id":"s9","realRepo":"acme/api","agent":"codex","dev":"ada","startedAt":"2026-11-14T09:00:00Z"}}""",
            "GET /api/v1/workflows" to """{"items":[$definition]}""",
            "GET /api/v1/workflows/wf1" to definition,
            "GET /api/v1/workflows/wf1/runs" to """{"items":[${run("r1", "waiting")}]}""",
        ),
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

    @Test
    fun theRunsAreFilteredAndARunShowsItsVisitsAndItsSession() {
        val server = server()
        signedIn(server) {
            click("tab-workflows")
            waitFor("workflow-run-r1")
            waitFor("workflow-run-r2")
            waitFor("workflow-run-step-r1")
            click("runs-filter-failed")
            waitFor("workflow-run-r3")
            waitFor("workflow-run-failure-r3")
            onNodeWithTag("workflow-run-r1").assertDoesNotExist()
            click("runs-filter-all")
            click("workflow-run-r1")
            waitFor("workflow-run-overview")
            waitFor("workflow-visit-1")
            waitForText("412 tests passed")
            waitFor("workflow-visit-waiting-2")
            waitForText("version: 1.4.0")
            click("workflow-run-session")
            waitFor("session-overview")
            waitForText("codex")
        }
    }

    @Test
    fun aWorkflowIsReadOnlyWithItsInputsNodesAndRuns() {
        val server = server()
        signedIn(server) {
            click("tab-workflows")
            click("workflows-part-definitions")
            waitFor("workflows-edit-note")
            click("workflow-wf1")
            waitFor("workflow-overview")
            waitFor("workflow-inputs")
            waitForText("Version")
            waitFor("workflow-node-build")
            waitForText("./gradlew build")
            waitFor("workflow-run-r1")
            assertTrue("GET /api/v1/workflows/wf1/runs" in server.asked)
            click("workflow-run-r1")
            waitFor("workflow-run-overview")
        }
    }

    @Test
    fun theDemoShowsItsWorkflowsAndRunsFromMemory() {
        val server = server()
        testApp(server) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            click("tab-workflows")
            waitFor("workflow-run-${DemoWorkflowIds.RUN_WAITING}")
            onNodeWithTag("workflow-run-${DemoWorkflowIds.RUN_DONE}").assertExists()
            click("workflow-run-${DemoWorkflowIds.RUN_WAITING}")
            waitFor("workflow-visit-4")
            click("back")
            click("workflows-part-definitions")
            waitFor("workflow-${DemoWorkflowIds.RELEASE}")
            onNodeWithTag("workflow-${DemoWorkflowIds.TRIAGE}").assertExists()
        }
        assertTrue(server.clients == 0, "the demo makes no request")
    }
}
