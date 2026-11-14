// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/jvmTest/.../WorkflowEditScreenTest.kt; see NOTICE.
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
import androidx.compose.ui.test.performTextReplacement
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import run.ostrom.app.demo.DemoWorkflowIds
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The workflow editor on the desktop target: create, edit and save, a stale save, a held save, a deletion, the rights, the demo. */
@OptIn(ExperimentalTestApi::class)
class WorkflowEditorScreenTest {
    private val origin = "https://ostrom.example.org"

    private fun definition(id: String, name: String, version: Int) = """{"id":"$id","name":"$name","domain":"engineering","version":$version,
        "nodes":[{"id":"write","kind":"agent","label":"Write the notes","run":{"instructions":"Notes for {{inputs.version}}"}}],
        "edges":[{"from":"write","to":null,"on":"delivered"}],"start":"write",
        "inputs":[{"id":"version","label":"Version","required":true}],
        "status":"accepted","foundAt":"2026-11-01T10:00:00Z"}"""

    private val pending = """{"confirmationId":"c1","confirmUrl":"$origin/confirm/c1","expiresAt":"2026-11-14T10:15:00Z"}"""

    private fun server(canEdit: Boolean? = true) = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":["workflows.v1"]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            "GET /api/v1/runs" to """{"items":[]}""",
            "GET /api/v1/me/organizations" to """{"items":[{"id":"o1","slug":"acme","name":"Acme","avatarUrl":"","role":"owner"}]}""",
            "GET /api/v1/workflows" to """{"items":[${definition("wf1", "Release", 3)}]}""",
            "GET /api/v1/workflows/wf1" to definition("wf1", "Release", 3),
            "GET /api/v1/workflows/wf1/runs" to """{"items":[]}""",
            "GET /api/v1/workflows/wf_new" to definition("wf_new", "Triage", 1),
            "GET /api/v1/workflows/wf_new/runs" to """{"items":[]}""",
            "POST /api/v1/workflows" to definition("wf_new", "Triage", 1),
            "PUT /api/v1/workflows/wf1" to definition("wf1", "Ship", 4),
            "DELETE /api/v1/workflows/wf1" to "",
        ).apply {
            if (canEdit != null) put("GET /api/v1/workflows/settings", """{"editors":{"mode":"members","memberIds":["m1","m2"]},"canEdit":$canEdit}""")
        },
        mutableMapOf("POST /api/v1/workflows" to 201, "DELETE /api/v1/workflows/wf1" to 204),
    )

    private fun signedIn(server: FakeServer, links: RecordingUriHandler = RecordingUriHandler(), block: DesktopComposeUiTest.() -> Unit) {
        val settings = MapSettings()
        SettingsAccountRepository(settings).save(Account(id = "a1", origin = origin, login = "ada", method = SignInMethod.DEVICE))
        val credentials = MemoryCredentialStore().also { runBlocking { it.write("a1", "osm_token") } }
        testApp(server, credentials = credentials, settings = settings, links = links) { block() }
    }

    private fun DesktopComposeUiTest.waitFor(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.waitForText(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.click(tag: String) {
        waitFor(tag)
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun DesktopComposeUiTest.definitions() {
        click("tab-workflows")
        click("workflows-part-definitions")
        waitFor("workflow-wf1")
    }

    private fun DesktopComposeUiTest.editWorkflow() {
        definitions()
        click("workflow-wf1")
        click("workflow-edit")
        waitFor("wfe-name")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Release")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anEditorCreatesAWorkflowWhoseRulesAreCheckedAsTyped() {
        val server = server()
        signedIn(server) {
            definitions()
            click("wfe-section-editors-header")
            waitFor("wfe-editors-rule")
            waitForText("Owners, admins and 2 chosen members")
            click("workflows-new")
            waitFor("wfe-save")
            waitFor("wfe-problems-count")
            onNodeWithTag("wfe-save").assertIsNotEnabled()
            onNodeWithTag("wfe-name").performTextInput("Triage")
            onNodeWithTag("wfe-n0-title").performTextInput("Reproduce")
            onNodeWithTag("wfe-n0-instruction").performTextInput("Write a failing test for {{inputs.ghost}}")
            waitForText("{{inputs.ghost}} is not known")
            onNodeWithTag("wfe-n0-instruction").performTextReplacement("Write a failing test.")
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("wfe-problems-count")).fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("wfe-save").assertIsEnabled()
            click("wfe-save")
            waitFor("workflow-overview")
            waitForText("Triage")
            val sent = server.bodies["POST /api/v1/workflows"].orEmpty()
            assertTrue(""""name":"Triage"""" in sent && """"instructions":"Write a failing test."""" in sent && """"status":"accepted"""" in sent, sent)
        }
    }

    @Test
    fun anEditIsSavedOverTheVersionRead() {
        val server = server()
        signedIn(server) {
            editWorkflow()
            onNodeWithTag("wfe-name").performTextReplacement("Ship")
            click("wfe-save")
            waitUntil(timeoutMillis = 10_000) { "PUT /api/v1/workflows/wf1" in server.asked }
            waitFor("workflow-overview")
            val sent = server.bodies["PUT /api/v1/workflows/wf1"].orEmpty()
            assertTrue(""""expectedVersion":3""" in sent && """"name":"Ship"""" in sent, sent)
        }
    }

    @Test
    fun aSaveOverANewerVersionOffersToReloadIt() {
        val server = server()
        server.failures["PUT /api/v1/workflows/wf1"] = 409 to "workflow_conflict"
        signedIn(server) {
            editWorkflow()
            onNodeWithTag("wfe-name").performTextReplacement("Ship")
            click("wfe-save")
            waitFor("wfe-conflict")
            waitForText("Someone saved this workflow since you opened it. Reload it to see their version.")
            server.routes["GET /api/v1/workflows/wf1"] = definition("wf1", "Release by Grace", 4)
            server.failures.remove("PUT /api/v1/workflows/wf1")
            click("wfe-conflict-reload")
            waitForText("Release by Grace")
            onNodeWithTag("wfe-conflict").assertDoesNotExist()
            click("wfe-save")
            waitUntil(timeoutMillis = 10_000) { server.bodies["PUT /api/v1/workflows/wf1"].orEmpty().contains(""""expectedVersion":4""") }
        }
    }

    @Test
    fun aSaveHeldForTheConsoleSaysSoAndOpensItsPage() {
        val server = server()
        server.statuses["PUT /api/v1/workflows/wf1"] = 202
        server.routes["PUT /api/v1/workflows/wf1"] = pending
        val links = RecordingUriHandler()
        signedIn(server, links) {
            editWorkflow()
            onNodeWithTag("wfe-name").performTextReplacement("Ship")
            click("wfe-save")
            waitFor("wfe-held")
            waitForText("Waiting for a confirmation")
            onNodeWithTag("wfe-save").assertIsNotEnabled()
            click("wfe-held-open")
            waitUntil(timeoutMillis = 10_000) { links.opened.isNotEmpty() }
            assertEquals(listOf("$origin/confirm/c1"), links.opened)
        }
    }

    @Test
    fun aHeldChangeOnAnotherHostIsNotOpened() {
        val server = server()
        server.statuses["DELETE /api/v1/workflows/wf1"] = 202
        server.routes["DELETE /api/v1/workflows/wf1"] = pending.replace(origin, "https://evil.test")
        signedIn(server) {
            definitions()
            click("workflow-wf1")
            click("workflow-delete")
            click("workflow-delete-yes")
            waitFor("workflow-delete-held")
            waitFor("workflow-delete-held-no-link")
            onNodeWithTag("workflow-delete-held-open").assertDoesNotExist()
        }
    }

    @Test
    fun aWorkflowIsDeletedOnceConfirmed() {
        val server = server()
        signedIn(server) {
            definitions()
            click("workflow-wf1")
            click("workflow-delete")
            waitFor("workflow-delete-confirm")
            click("workflow-delete-keep")
            assertTrue("DELETE /api/v1/workflows/wf1" !in server.asked)
            click("workflow-delete")
            server.routes["GET /api/v1/workflows"] = """{"items":[]}"""
            click("workflow-delete-yes")
            waitUntil(timeoutMillis = 10_000) { "DELETE /api/v1/workflows/wf1" in server.asked }
            waitFor("workflow-definitions-empty")
        }
    }

    @Test
    fun whoMayNotDecideSeesNoEditorEntry() {
        val server = server(canEdit = false)
        signedIn(server) {
            definitions()
            waitUntil(timeoutMillis = 10_000) { "GET /api/v1/workflows/settings" in server.asked }
            click("wfe-section-editors-header")
            waitFor("wfe-editors-rule")
            onNodeWithTag("workflows-new").assertDoesNotExist()
            click("workflow-wf1")
            waitFor("workflow-overview")
            waitUntil(timeoutMillis = 10_000) { server.asked.count { it == "GET /api/v1/workflows/settings" } >= 2 }
            onNodeWithTag("workflow-edit").assertDoesNotExist()
            onNodeWithTag("workflow-delete").assertDoesNotExist()
        }
    }

    @Test
    fun whenTheRightsCannotBeReadTheEntriesShowAndARefusalIsWordedByItsKey() {
        val server = server(canEdit = null)
        server.failures["PUT /api/v1/workflows/wf1"] = 403 to "workflow_editors_only"
        signedIn(server) {
            editWorkflow()
            click("wfe-save")
            waitFor("wfe-save-error")
            waitForText("You may not decide this workflow")
        }
    }

    @Test
    fun theDemoCreatesEditsAndDeletesWorkflowsInMemory() {
        val server = server()
        testApp(server) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            click("tab-workflows")
            click("workflows-part-definitions")
            click("workflow-${DemoWorkflowIds.TRIAGE}")
            click("workflow-edit")
            waitFor("wfe-name")
            onNodeWithTag("wfe-name").performTextReplacement("Triage a report")
            click("wfe-save")
            waitFor("workflow-overview")
            waitForText("Triage a report")
            click("workflow-delete")
            click("workflow-delete-yes")
            waitFor("workflow-${DemoWorkflowIds.RELEASE}")
            onNodeWithTag("workflow-${DemoWorkflowIds.TRIAGE}").assertDoesNotExist()
        }
        assertTrue(server.clients == 0, "the demo makes no request")
    }
}
