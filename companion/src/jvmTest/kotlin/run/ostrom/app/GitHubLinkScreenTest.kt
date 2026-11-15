// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/jvmTest/.../GitHubLinkScreenTest.kt; see NOTICE.
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings' GitHub row and screen on the desktop target: the row only with `github.link.v1`, the states of the link,
 * the device flow's code shown and GitHub's page opened, then linked, unlinked, refused by key, and the demo.
 */
@OptIn(ExperimentalTestApi::class)
class GitHubLinkScreenTest {
    private val origin = "https://ostrom.example.org"
    private val notLinked = """{"memberId":"m1","state":"not_linked","appConfigured":true}"""
    private val linked = """{"memberId":"m1","state":"linked","appConfigured":true,"login":"octocat","userId":1,"linkedAt":"2026-11-14T09:00:00Z"}"""
    private val device = """{"id":"d1","userCode":"ABCD-1234","verificationUri":"https://github.com/login/device","expiresAt":"2036-11-14T09:15:00Z","interval":1}"""

    private fun server(features: String = "\"github.link.v1\"", link: String = notLinked) = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":[$features]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            "GET /api/v1/github/link" to link,
            "POST /api/v1/github/link/device" to device,
            "POST /api/v1/github/link/device/d1" to """{"status":"pending"}""",
            "DELETE /api/v1/github/link" to "",
        ),
        statuses = mutableMapOf("DELETE /api/v1/github/link" to 204),
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
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.tap(tag: String) {
        waitFor(tag)
        onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun DesktopComposeUiTest.openGitHub() {
        waitFor("tab-settings")
        onNodeWithTag("tab-settings").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("settings")
        tap("settings-github")
    }

    @Test
    fun aLinkedAccountShowsItsLoginAndCanBeUnlinked() {
        val server = server(link = linked)
        signedIn(server) {
            openGitHub()
            waitFor("github-login")
            waitForText("Linked as octocat")
            waitFor("github-start")
            server.routes["GET /api/v1/github/link"] = notLinked
            tap("github-unlink")
            waitFor("github-not-linked")
            assertTrue("DELETE /api/v1/github/link" in server.asked)
            onNodeWithTag("github-unlink").assertDoesNotExist()
        }
    }

    @Test
    fun theDeviceFlowShowsTheCodeOpensGitHubOnceThenLinks() {
        val links = RecordingUriHandler()
        val server = server()
        signedIn(server, links) {
            openGitHub()
            waitFor("github-not-linked")
            tap("github-start")
            waitFor("github-code")
            waitForText("ABCD-1234")
            waitFor("github-waiting")
            assertEquals(listOf("https://github.com/login/device"), links.opened)
            tap("github-open")
            assertEquals(2, links.opened.size)
            // GitHub's answer: the account is linked.
            server.routes["POST /api/v1/github/link/device/d1"] = """{"status":"linked","link":$linked}"""
            server.routes["GET /api/v1/github/link"] = linked
            waitFor("github-login")
            waitFor("github-linked")
            onNodeWithTag("github-code").assertDoesNotExist()
        }
    }

    @Test
    fun anExpiredCodeSaysSoAndACancelStopsTheFlow() {
        val server = server()
        signedIn(server) {
            openGitHub()
            tap("github-start")
            waitFor("github-code")
            server.routes["POST /api/v1/github/link/device/d1"] = """{"status":"expired"}"""
            waitFor("github-ended")
            waitForText("The code expired")
            tap("github-start")
            waitFor("github-code")
            tap("github-cancel")
            waitFor("github-start")
            onNodeWithTag("github-code").assertDoesNotExist()
        }
    }

    @Test
    fun aRefusedStartIsWordedByItsKeyNeverByTheServersText() {
        val server = server()
        server.failures["POST /api/v1/github/link/device"] = 409 to "github_device_flow_disabled"
        signedIn(server) {
            openGitHub()
            tap("github-start")
            waitFor("github-error")
            waitForText("does not allow the device flow")
            assertTrue(onAllNodes(hasText("\"m\"", substring = true)).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun anInstanceWithoutItsAppAndAReconnectAreWorded() {
        val server = server(link = """{"memberId":"m1","state":"not_linked","appConfigured":false}""")
        signedIn(server) {
            openGitHub()
            waitFor("github-not-linked")
            waitForText("has not configured the instance's GitHub App")
            onNodeWithTag("github-start").assertDoesNotExist()
            server.routes["GET /api/v1/github/link"] = """{"memberId":"m1","state":"reconnect_required","appConfigured":true,"login":"octocat"}"""
            waitFor("github-refresh")
            onNodeWithTag("github-refresh").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("github-reconnect")
            waitForText("Link again")
        }
    }

    @Test
    fun theRowNeedsTheFeature() {
        signedIn(server("\"gates.v1\"")) {
            waitFor("tab-settings")
            onNodeWithTag("tab-settings").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("settings")
            waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithTag("server-version").assertExists() }.isSuccess }
            onNodeWithTag("settings-github").assertDoesNotExist()
        }
    }

    @Test
    fun theDemoLinksItsOwnAccountWithoutAnyRequest() {
        val server = server()
        testApp(server) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            openGitHub()
            waitFor("github-not-linked")
            tap("github-start")
            waitFor("github-code")
            waitFor("github-login")
            waitForText("Linked as ada-lovelace")
            tap("github-unlink")
            waitFor("github-not-linked")
        }
        assertFalse(server.clients > 0, "the demo makes no request")
    }
}
