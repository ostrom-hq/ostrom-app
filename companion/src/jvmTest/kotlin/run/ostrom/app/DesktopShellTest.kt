// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion jvmTest DesktopShellTest.kt and UpdateBannerTest.kt (Apache-2.0); see NOTICE.
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module
import run.ostrom.app.di.updateModule
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.services.CliUpdater
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import run.ostrom.app.presentation.navigation.AppCommand
import run.ostrom.app.presentation.navigation.AppCommands
import run.ostrom.app.presentation.screens.MainTab
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the desktop adds to the shared app: the CLI's account, the CLI's update banner, the menu bar's commands. */
@OptIn(ExperimentalTestApi::class)
class DesktopShellTest {
    private val origin = "https://ostrom.example.org"

    private fun server() = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":["gates.v1"]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/approvals" to """{"items":[]}""",
        ),
    )

    private class FakeUpdater : CliUpdater {
        val updates = mutableListOf<String>()
        override suspend fun installedVersion(): String = if (updates.isEmpty()) "0.4.1" else "0.5.0"
        override suspend fun latestVersion(): String = "0.5.0"
        override suspend fun update(output: (String) -> Unit): String {
            output("Downloading ostrom 0.5.0")
            updates += "update"
            return "0.5.0"
        }
    }

    private fun signedIn(id: String = "a1", signedIn: Boolean = true): Pair<MapSettings, MemoryCredentialStore> {
        val settings = MapSettings()
        SettingsAccountRepository(settings).save(Account(id = id, origin = origin, login = "ada", method = SignInMethod.DEVICE, signedIn = signedIn))
        val credentials = MemoryCredentialStore().also { if (signedIn) runBlocking { it.write(id, "osm_desktop") } }
        return settings to credentials
    }

    @Test
    fun theCliAccountSignedOutSaysToRunOstromLogin() {
        val (settings, credentials) = signedIn(Account.CLI_ACCOUNT_ID, signedIn = false)
        testApp(server(), settings = settings, credentials = credentials) {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("signed-out")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("Run ostrom login in a terminal", substring = true).assertExists()
            onNodeWithTag("tab-settings").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("cli-badge").assertExists()
            onNodeWithTag("cli-account-hint").assertTextContains("runs ostrom logout", substring = true)
            onNodeWithTag("sign-in-again").assertDoesNotExist()
        }
    }

    @Test
    fun theBannerUpdatesTheCliAndSaysSo() {
        val (settings, credentials) = signedIn()
        val updater = FakeUpdater()
        val desktop = module {
            includes(updateModule)
            single<CliUpdater> { updater }
        }
        testApp(server(), settings = settings, credentials = credentials, extra = desktop) {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("update-banner-update")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithText("ostrom 0.5.0 is available").assertExists()
            onNodeWithTag("update-banner-detail").assertTextContains("0.4.1", substring = true)
            onNodeWithTag("update-banner-update").performSemanticsAction(SemanticsActions.OnClick)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("update-banner-update")).fetchSemanticsNodes().isEmpty() }
            settle()
            onNodeWithText("ostrom 0.5.0 is installed").assertExists()
            assertEquals(listOf("update"), updater.updates)
            onNodeWithTag("update-banner-dismiss").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            onNodeWithTag("update-banner").assertDoesNotExist()
        }
    }

    @Test
    fun withoutAnUpdaterThereIsNoBanner() {
        val (settings, credentials) = signedIn()
        testApp(server(), settings = settings, credentials = credentials) {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("tabs")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("update-banner").assertDoesNotExist()
        }
    }

    @Test
    fun theMenuBarShowsATab() {
        val (settings, credentials) = signedIn()
        testApp(server(), settings = settings, credentials = credentials) {
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("tabs")).fetchSemanticsNodes().isNotEmpty() }
            onNodeWithTag("settings").assertDoesNotExist()
            // The menu bar's items run on the window's thread.
            runOnUiThread { AppCommands.send(AppCommand.ShowTab(MainTab.SETTINGS)) }
            settle()
            onNodeWithTag("settings").assertExists()
            runOnUiThread { AppCommands.send(AppCommand.AddAccount) }
            settle()
            onNodeWithTag("origin").assertExists()
        }
    }
}
