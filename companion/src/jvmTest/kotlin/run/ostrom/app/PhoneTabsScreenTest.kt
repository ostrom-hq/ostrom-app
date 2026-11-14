// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import run.ostrom.app.presentation.navigation.AppCommand
import run.ostrom.app.presentation.navigation.AppCommands
import run.ostrom.app.presentation.screens.MainTab
import kotlin.test.Test

/** The phone's bar holds five labeled tabs and Settings opens the others; a wide window's rail keeps every tab. */
@OptIn(ExperimentalTestApi::class)
class PhoneTabsScreenTest {
    private val origin = "https://ostrom.example.org"
    private val features = listOf("gates.v1", "waits.v1", "sessions.v1", "receipts.v1", "budgets.v1", "artifacts.v1", "workflows.v1")

    private fun server() = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":[${features.joinToString(",") { "\"$it\"" }}]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/approvals" to """{"items":[]}""",
            "GET /api/v1/waits" to """{"items":[]}""",
            "GET /api/v1/sessions" to """{"items":[]}""",
            "GET /api/v1/receipts" to """{"items":[]}""",
            "GET /api/v1/receipts/keys" to """{"keys":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            "GET /api/v1/artifacts" to """{"items":[]}""",
            "GET /api/v1/runs" to """{"items":[]}""",
            "GET /api/v1/workflows" to """{"items":[]}""",
        ),
    )

    private fun signedIn(width: Int, height: Int, block: DesktopComposeUiTest.() -> Unit) {
        val settings = MapSettings()
        SettingsAccountRepository(settings).save(Account(id = "a1", origin = origin, login = "ada", method = SignInMethod.DEVICE))
        val credentials = MemoryCredentialStore().also { runBlocking { it.write("a1", "osm_token") } }
        testApp(server(), width = width, height = height, credentials = credentials, settings = settings) { block() }
    }

    private fun DesktopComposeUiTest.waitFor(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.waitGone(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }

    private fun DesktopComposeUiTest.click(tag: String) {
        waitFor(tag)
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private val inBar = listOf("inbox" to "Approvals", "waiting" to "Waiting", "sessions" to "Sessions", "workflows" to "Workflows", "settings" to "Settings")
    private val inSettings = listOf("history", "spending", "activity", "artifacts")

    @Test
    fun aPhonesBarHasFiveLabeledTabsAndSettingsOpensTheOthersWithBack() {
        signedIn(width = 360, height = 780) {
            waitFor("tab-workflows")
            inBar.forEach { (tag, label) ->
                // Every label shows in its own item, not only the selected tab's.
                onNodeWithTag("tab-$tag").assertIsDisplayed().assertTextEquals(label)
            }
            inSettings.forEach { onNodeWithTag("tab-$it").assertDoesNotExist() }
            click("tab-settings")
            inSettings.forEach { waitFor("settings-more-$it") }
            // A row opens its screen above the tabs; back returns to Settings.
            click("settings-more-history")
            waitFor("more-tab-history")
            waitFor("history")
            onNodeWithTag("tabs").assertDoesNotExist()
            click("back")
            waitGone("more-tab-history")
            waitFor("settings-more-spending")
            click("settings-more-activity")
            waitFor("more-tab-activity")
            click("back")
            waitFor("settings")
        }
    }

    @Test
    fun aTabAskedForOnAPhoneOpensAboveSettingsWhenTheBarHasNoRoomForIt() {
        signedIn(width = 360, height = 780) {
            waitFor("tab-workflows")
            // The desktop's menu bar (a narrow window) or a pushed screen asks for the History tab.
            runOnUiThread { AppCommands.send(AppCommand.ShowTab(MainTab.HISTORY)) }
            waitFor("more-tab-history")
            click("back")
            waitFor("settings-more-history")
            // A tab of the bar still opens as a tab.
            runOnUiThread { AppCommands.send(AppCommand.ShowTab(MainTab.WAITING)) }
            waitGone("settings")
            onNodeWithTag("more-tab-history").assertDoesNotExist()
        }
    }

    @Test
    fun aWideWindowsRailKeepsEveryTabAndSettingsHasNoMoreSection() {
        signedIn(width = 1100, height = 800) {
            waitFor("tab-workflows")
            (inBar.map { it.first } + inSettings).forEach { onNodeWithTag("tab-$it").assertExists() }
            click("tab-settings")
            waitFor("settings")
            onNodeWithTag("settings-more").assertDoesNotExist()
            // The menu bar reaches each of them as a tab.
            runOnUiThread { AppCommands.send(AppCommand.ShowTab(MainTab.SPENDING)) }
            waitGone("settings")
            onNodeWithTag("more-tab-spending").assertDoesNotExist()
        }
    }
}
