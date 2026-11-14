// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
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
 * Settings' environment and MCP servers, read only: the names an agent receives and those withheld (never a value),
 * the personal variants, the personal MCP servers with their state, the console to change them, each row only with its
 * feature, the demo.
 */
@OptIn(ExperimentalTestApi::class)
class MemberSetupScreenTest {
    private val origin = "https://ostrom.example.org"

    private fun server(features: String = "\"environment.v1\",\"mcp.personal.v1\"") = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":[$features]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            "GET /api/v1/environment" to """{"variables":{"API_KEY":"secret-value-a"},"unavailable":["STRIPE_KEY"]}""",
            "GET /api/v1/me/environment/variants" to """{"items":[{"id":"v1","variable":"API_KEY","name":"My key","binding":"shared",""" +
                """"owner":"m1","hasValue":true,"version":1,"createdAt":"2026-11-13T10:00:00Z","updatedAt":"2026-11-13T10:00:00Z"}]}""",
            "GET /api/v1/me/mcp/servers" to """{"items":[{"name":"notes","transport":"stdio","enabled":true,"scope":"org",""" +
                """"agentName":"ostrom-notes","state":"failed","failure":"start_failed","stdio":{"launcher":"npm","packageName":"notes@1.0.0"}}]}""",
        ),
    )

    private fun signedIn(server: FakeServer, links: RecordingUriHandler = RecordingUriHandler(), block: DesktopComposeUiTest.() -> Unit) {
        val settings = MapSettings()
        SettingsAccountRepository(settings).save(Account(id = "a1", origin = origin, login = "ada", method = SignInMethod.DEVICE))
        val credentials = MemoryCredentialStore().also { runBlocking { it.write("a1", "osm_token") } }
        testApp(server, credentials = credentials, settings = settings, links = links) { block() }
    }

    private fun DesktopComposeUiTest.waitFor(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun DesktopComposeUiTest.openSettings() {
        waitFor("tab-settings")
        onNodeWithTag("tab-settings").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("settings")
    }

    @Test
    fun theEnvironmentShowsNamesOnlyTheWithheldOnesAndThePersonalValues() {
        val links = RecordingUriHandler()
        val server = server()
        signedIn(server, links) {
            openSettings()
            waitFor("settings-environment")
            tap("settings-environment")
            waitFor("member-env-name-API_KEY")
            onNodeWithTag("member-env-unavailable-STRIPE_KEY").performScrollTo().assertIsDisplayed()
            onNodeWithTag("member-env-variant-v1").performScrollTo().assertIsDisplayed()
            assertTrue(onAllNodes(hasText("secret-value-a", substring = true)).fetchSemanticsNodes().isEmpty(), "a value is never shown")
            tap("member-env-open-console")
            assertEquals(listOf("$origin/me/environment"), links.opened)
        }
    }

    @Test
    fun theMcpServersShowTheirSourceAndStateAndAFailedConsoleShowsItsAddress() {
        val links = RecordingUriHandler(fails = true)
        signedIn(server(), links) {
            openSettings()
            waitFor("settings-mcp")
            tap("settings-mcp")
            waitFor("member-mcp-notes")
            onNodeWithTag("member-mcp-source-notes").assertIsDisplayed()
            onNodeWithTag("member-mcp-state-notes").assertIsDisplayed()
            assertTrue(onAllNodes(hasText("start_failed", substring = true)).fetchSemanticsNodes().isNotEmpty())
            tap("member-mcp-open-console")
            waitFor("member-mcp-console-url")
            tap("member-mcp-copy-console")
        }
    }

    @Test
    fun aFailedReadSaysSoAndRetries() {
        val server = server()
        server.failures["GET /api/v1/me/mcp/servers"] = 500 to "internal_error"
        signedIn(server) {
            openSettings()
            waitFor("settings-mcp")
            tap("settings-mcp")
            waitFor("member-mcp-error")
            server.failures.clear()
            tap("member-mcp-retry")
            waitFor("member-mcp-notes")
        }
    }

    @Test
    fun anEmptySetupSaysSo() {
        val server = server()
        server.routes["GET /api/v1/environment"] = """{"variables":{},"unavailable":[]}"""
        server.routes["GET /api/v1/me/environment/variants"] = """{"items":[]}"""
        server.routes["GET /api/v1/me/mcp/servers"] = """{"items":[]}"""
        signedIn(server) {
            openSettings()
            waitFor("settings-environment")
            tap("settings-environment")
            waitFor("member-env-names-empty")
            onNodeWithTag("member-env-variants-empty").assertIsDisplayed()
            onNodeWithTag("member-env-unavailable").assertDoesNotExist()
        }
    }

    @Test
    fun eachRowNeedsItsFeature() {
        signedIn(server("\"mcp.personal.v1\"")) {
            openSettings()
            waitFor("settings-mcp")
            onNodeWithTag("settings-environment").assertDoesNotExist()
        }
        signedIn(server("\"gates.v1\"")) {
            openSettings()
            // Once the server's features are known.
            waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithTag("server-version").assertTextEquals("1.4.0") }.isSuccess }
            onNodeWithTag("settings-mcp").assertDoesNotExist()
            onNodeWithTag("settings-environment").assertDoesNotExist()
        }
    }

    @Test
    fun theDemoShowsItsSetupWithoutAConsole() {
        val server = server()
        testApp(server) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            openSettings()
            waitFor("settings-environment")
            tap("settings-environment")
            waitFor("member-env-name-OPENAI_API_KEY")
            onNodeWithTag("member-env-open-console").assertDoesNotExist()
        }
        assertFalse(server.clients > 0, "the demo makes no request")
    }
}
