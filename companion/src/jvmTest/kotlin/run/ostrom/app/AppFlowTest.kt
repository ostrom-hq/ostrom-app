// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import run.ostrom.app.demo.DemoContent
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The whole app on the desktop: add a server, sign in with the device flow, the settings, the demo, a server too new. */
@OptIn(ExperimentalTestApi::class)
class AppFlowTest {
    private val origin = "https://ostrom.example.org"

    private fun server(apiVersion: Int = 1) = FakeServer(
        mutableMapOf(
            "GET /api/v1/auth/providers" to """{"methods":["github"],"signUp":"open"}""",
            "POST /api/v1/auth/device" to """{"deviceCode":"dc","userCode":"WDJB-MJHT","verificationUri":"https://github.com/login/device","interval":1,"expiresIn":60}""",
            "POST /api/v1/auth/device/token" to """{"token":"osm_desktop","login":"ada","scopes":[]}""",
            "GET /api/v1/capabilities" to """{"apiVersion":$apiVersion,"serverVersion":"1.4.0","features":["gates.v1"]}""",
            "GET /api/v1/me/organizations" to """{"items":[{"id":"o1","slug":"ae","name":"Analytical Engines","avatarUrl":"","role":"owner"}]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "POST /api/v1/auth/logout" to """{}""",
        ),
    )

    @Test
    fun aComputerSignsInWithTheDeviceFlowAndSeesTheVersions() {
        val server = server()
        val links = RecordingUriHandler()
        val credentials = MemoryCredentialStore()
        testApp(server, credentials = credentials, links = links) { koin ->
            onNodeWithTag("origin").performTextInput("ostrom.example.org")
            onNodeWithTag("check").performSemanticsAction(SemanticsActions.OnClick)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("server")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText(origin).assertExists()
            onNodeWithTag("continue").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("submit")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("login-server").assertExists()
            onNodeWithText(origin).assertExists()
            onNodeWithTag("submit").performSemanticsAction(SemanticsActions.OnClick)
            // The code of `ostrom login`, and GitHub's page opened to enter it; then the server says yes. The server serves
            // gates.v1, so the approvals inbox is the first tab; Settings is the other.
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("tab-settings")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("tab-settings").performSemanticsAction(SemanticsActions.OnClick)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("settings")).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf("https://github.com/login/device"), links.opened)
            val account = koin.get<AccountRepository>().accounts.value.single()
            assertEquals("ada", account.login)
            assertEquals(SignInMethod.DEVICE, account.method)
            assertEquals("o1", account.organizationId)
            assertEquals("osm_desktop", runBlocking { credentials.read(account.id) })
            waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithTag("server-version").assertTextEquals("1.4.0") }.isSuccess }
            onNodeWithTag("app-version").assertTextEquals("9.9.9")
            onNodeWithTag("api-version").assertTextEquals("Server v1 · app v1")
            onNodeWithText("Analytical Engines").assertExists()
            // Signing out revokes the session and goes back to the first screen.
            onNodeWithTag("sign-out").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("origin")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue("POST /api/v1/auth/logout" in server.asked)
            assertTrue(koin.get<AccountRepository>().accounts.value.isEmpty())
            assertNull(runBlocking { credentials.read(account.id) })
        }
    }

    @Test
    fun aServerThatSpeaksAnotherApiIsRefusedAtSignIn() {
        testApp(server(apiVersion = 2)) { koin ->
            onNodeWithTag("origin").performTextInput(origin)
            onNodeWithTag("check").performSemanticsAction(SemanticsActions.OnClick)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("continue")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("continue").performSemanticsAction(SemanticsActions.OnClick)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("submit")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("submit").performSemanticsAction(SemanticsActions.OnClick)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("error")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("another version of the API", substring = true).assertExists()
            assertTrue(koin.get<AccountRepository>().accounts.value.isEmpty())
        }
    }

    @Test
    fun anAccountWhoseServerMovedToAnotherApiIsBlockedUntilSignOut() {
        val settings = MapSettings()
        SettingsAccountRepository(settings).save(Account(id = "a1", origin = origin, login = "ada", method = SignInMethod.DEVICE))
        val credentials = MemoryCredentialStore().also { runBlocking { it.write("a1", "osm_old") } }
        testApp(server(apiVersion = 2), credentials = credentials, settings = settings) { koin ->
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("incompatible-message")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("incompatible-message").assertTextContains("API v2", substring = true)
            onNodeWithTag("incompatible-sign-out").performSemanticsAction(SemanticsActions.OnClick)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("origin")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(koin.get<AccountRepository>().accounts.value.isEmpty())
        }
    }

    @Test
    fun theDemoRunsWithoutAnyNetworkAndEndsFromSettings() {
        val server = server()
        testApp(server) { koin ->
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("demo-banner")).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(DemoContent.ACCOUNT_ID, koin.get<AccountRepository>().activeAccountId.value)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("tab-settings")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("tab-settings").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("server-version").assertTextEquals("demo")
            onNodeWithTag("add-account").assertDoesNotExist()
            onNodeWithTag("sign-out").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("origin")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(koin.get<AccountRepository>().accounts.value.isEmpty())
        }
        assertEquals(0, server.clients)
    }
}
