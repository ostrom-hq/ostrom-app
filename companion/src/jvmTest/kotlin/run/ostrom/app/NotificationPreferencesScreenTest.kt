// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion jvmTest NotificationPreferencesScreenTest.kt (Apache-2.0); see NOTICE.
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
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
import kotlin.test.assertTrue

/** Settings' notification preferences: a switch saved at once, a refused change undone, quiet hours, no screen without the feature, the demo. */
@OptIn(ExperimentalTestApi::class)
class NotificationPreferencesScreenTest {
    private val origin = "https://ostrom.example.org"
    private val read = "GET /api/v1/me/notifications"
    private val save = "PUT /api/v1/me/notifications"

    private fun server(features: String = "\"notifications.v1\"") = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":[$features]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            read to """{"requests":true,"decisions":true,"spending":true}""",
            save to """{"requests":false,"decisions":true,"spending":true}""",
        ),
    )

    private fun signedIn(server: FakeServer, block: DesktopComposeUiTest.() -> Unit) {
        val settings = MapSettings()
        SettingsAccountRepository(settings).save(Account(id = "a1", origin = origin, login = "ada", method = SignInMethod.DEVICE))
        val credentials = MemoryCredentialStore().also { runBlocking { it.write("a1", "osm_token") } }
        testApp(server, credentials = credentials, settings = settings) { block() }
    }

    private fun DesktopComposeUiTest.waitFor(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun DesktopComposeUiTest.openPreferences() {
        waitFor("tab-settings")
        onNodeWithTag("tab-settings").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("settings-notifications")
        tap("settings-notifications")
        waitFor("notifications-requests")
    }

    @Test
    fun aSwitchIsSavedAtOnceAndShowsWhatTheServerHolds() {
        val server = server()
        signedIn(server) {
            openPreferences()
            onNodeWithTag("notifications-requests").assertIsOn()
            tap("notifications-requests")
            waitUntil(timeoutMillis = 10_000) { save in server.asked }
            val body = server.bodies.getValue(save)
            assertTrue("\"requests\":false" in body && "\"decisions\":true" in body, body)
            onNodeWithTag("notifications-requests").assertIsOff()
        }
    }

    @Test
    fun aRefusedChangeIsUndoneAndSaidSo() {
        val server = server().also { it.failures[save] = 503 to "unavailable" }
        signedIn(server) {
            openPreferences()
            tap("notifications-spending")
            waitFor("notifications-save-failed")
            onNodeWithTag("notifications-spending").assertIsOn()
        }
    }

    @Test
    fun quietHoursTurnOnAtNightInThePhonesZone() {
        val server = server()
        server.routes[save] = """{"requests":true,"decisions":true,"spending":true,"quietHours":{"start":"22:00","end":"07:00","timeZone":"Europe/Paris"}}"""
        signedIn(server) {
            openPreferences()
            onNodeWithTag("notifications-quiet").assertIsOff()
            tap("notifications-quiet")
            waitFor("notifications-quiet-range")
            val body = server.bodies.getValue(save)
            assertTrue("\"start\":\"22:00\"" in body && "\"end\":\"07:00\"" in body && "\"timeZone\"" in body, body)
            onNodeWithTag("notifications-quiet").assertIsOn()
        }
    }

    @Test
    fun aServerWithoutPreferencesOffersNoScreen() {
        val server = server(features = "")
        signedIn(server) {
            waitFor("tab-settings")
            onNodeWithTag("tab-settings").performSemanticsAction(SemanticsActions.OnClick)
            // Once the server's features are known.
            waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithTag("server-version").assertTextEquals("1.4.0") }.isSuccess }
            onNodeWithTag("settings-notifications").assertDoesNotExist()
        }
        assertTrue(server.asked.none { it.endsWith("/me/notifications") }, server.asked.toString())
    }

    @Test
    fun theDemoKeepsItsPreferencesInMemory() {
        val server = server()
        testApp(server) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            openPreferences()
            tap("notifications-decisions")
            waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithTag("notifications-decisions").assertIsOff() }.isSuccess }
        }
        assertTrue(server.clients == 0, "the demo makes no request")
    }
}
