// SPDX-License-Identifier: Apache-2.0
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
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertTrue

/** Back and forth between a run, its session and its child run: never a second screen of the same one, and back leaves them. */
@OptIn(ExperimentalTestApi::class)
class RunSessionNavigationTest {
    private val origin = "https://ostrom.example.org"

    private fun run(id: String, name: String, extra: String = "") = """{"id":"$id","workflowId":"wf1","workflowName":"$name","workflowVersion":3,
        "status":"waiting","startedBy":"ada","source":"manual","startedAt":"2026-11-14T09:00:00Z"$extra}"""

    private fun server() = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":["workflows.v1","sessions.v1"]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            "GET /api/v1/sessions" to """{"items":[]}""",
            "GET /api/v1/workflows" to """{"items":[]}""",
            "GET /api/v1/runs" to """{"items":[${run("r1", "Release")}]}""",
            "GET /api/v1/runs/r1" to run("r1", "Release", ""","sessionId":"s9","visits":[
                {"seq":1,"nodeId":"sub","kind":"workflow","status":"active","nodeTitle":"Deploy","childRunId":"r2","startedAt":"2026-11-14T09:05:00Z"}]"""),
            "GET /api/v1/runs/r2" to run("r2", "Deploy", ""","parentRunId":"r1","sessionId":"s8","visits":[]"""),
            "GET /api/v1/sessions/s9" to """{"session":{"id":"s9","realRepo":"acme/api","agent":"codex","dev":"ada","startedAt":"2026-11-14T09:00:00Z","runId":"r1"}}""",
            "GET /api/v1/sessions/s8" to """{"session":{"id":"s8","realRepo":"acme/deploy","agent":"codex","dev":"ada","startedAt":"2026-11-14T09:05:00Z","runId":"r2"}}""",
        ),
    )

    private fun signedIn(server: FakeServer = server(), block: DesktopComposeUiTest.() -> Unit) {
        val settings = MapSettings()
        SettingsAccountRepository(settings).save(Account(id = "a1", origin = origin, login = "ada", method = SignInMethod.DEVICE))
        val credentials = MemoryCredentialStore().also { runBlocking { it.write("a1", "osm_token") } }
        testApp(server, credentials = credentials, settings = settings) { block() }
    }

    private fun DesktopComposeUiTest.waitFor(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.waitForText(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.waitGone(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

    private fun DesktopComposeUiTest.click(tag: String) {
        waitFor(tag)
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Back from a run or a session lands on the tabs: nothing of them was left below. */
    private fun DesktopComposeUiTest.backLandsOnTheTabs() {
        click("back")
        waitGone("workflow-run-overview")
        waitGone("session-overview")
        waitFor("workflow-run-r1")
    }

    @Test
    fun aRunItsSessionAndTheRunAgainKeepOneScreenOfEach() {
        val server = server()
        signedIn(server) {
            click("tab-workflows")
            click("workflow-run-r1")
            waitForText("Release, version 3")
            // Run, its session, its run, its session, its run: back to the screens already open, none pushed again.
            repeat(2) {
                click("workflow-run-session")
                waitFor("session-overview")
                waitForText("acme/api")
                click("session-open-run")
                waitFor("workflow-run-overview")
                waitForText("Release, version 3")
            }
            // The session shown again was read again (the server publishes no session event).
            assertTrue(server.asked.count { it == "GET /api/v1/sessions/s9" } >= 2, server.asked.toString())
            backLandsOnTheTabs()
        }
    }

    @Test
    fun aChildRunItsParentAndTheirSessionsGoBackToTheScreenAlreadyOpen() {
        signedIn {
            click("tab-workflows")
            click("workflow-run-r1")
            waitForText("Release, version 3")
            // The child run, then its parent: back to the parent already open below it.
            click("workflow-visit-child-1")
            waitForText("Deploy, version 3")
            click("workflow-run-parent")
            waitForText("Release, version 3")
            backLandsOnTheTabs()
            // The child's own session, then its run: back to that child, not to the parent of the same workflow.
            click("workflow-run-r1")
            click("workflow-visit-child-1")
            waitForText("Deploy, version 3")
            click("workflow-run-session")
            waitForText("acme/deploy")
            click("session-open-run")
            waitForText("Deploy, version 3")
            // Back once: the parent run it was opened from, then the tabs.
            click("back")
            waitForText("Release, version 3")
            backLandsOnTheTabs()
        }
    }
}
