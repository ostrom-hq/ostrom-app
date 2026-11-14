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
import androidx.compose.ui.test.onNodeWithText
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
import kotlin.test.assertTrue

/**
 * Settings' notifications: a switch per channel and kind saved at once, the requests and budgets with quiet hours, a
 * refused change undone, the test push and each phone's result, no screen without the feature, the demo.
 */
@OptIn(ExperimentalTestApi::class)
class NotificationPreferencesScreenTest {
    private val origin = "https://ostrom.example.org"
    private val read = "GET /api/v1/me/notifications"
    private val save = "PUT /api/v1/me/notifications"
    private val channels = "GET /api/v1/notifications/settings"
    private val setChannel = "PUT /api/v1/notifications/settings"
    private val test = "POST /api/v1/notifications/push/test"

    private fun server(features: String = "\"notifications.v1\"") = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":[$features]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            read to """{"requests":true,"decisions":true,"spending":true}""",
            save to """{"requests":false,"decisions":true,"spending":true}""",
            channels to """{"channels":["push"],"settings":[{"channel":"push","kind":"waiting","enabled":true}]}""",
            setChannel to """{"channels":["push"],"settings":[{"channel":"push","kind":"waiting","enabled":false}]}""",
            test to """{"ok":true,"results":[{"status":"sent"},{"status":"rejected","error":"bad_token"}]}""",
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
    fun aChannelSwitchIsSavedAtOnceAndTheTestNeedsItsFeature() {
        val server = server()
        signedIn(server) {
            openPreferences()
            waitFor("notifications-switch-push-waiting")
            onNodeWithTag("notifications-switch-push-turn_failed").assertIsOn()
            tap("notifications-switch-push-waiting")
            waitUntil(timeoutMillis = 10_000) { setChannel in server.asked }
            assertEquals("""{"channel":"push","kind":"waiting","enabled":false}""", server.bodies.getValue(setChannel))
            onNodeWithTag("notifications-switch-push-waiting").assertIsOff()
            onNodeWithTag("notifications-test").assertDoesNotExist()
        }
    }

    @Test
    fun aTestShowsEachPhonesResult() {
        val server = server(features = "\"notifications.v1\",\"notifications.push.test.v1\"")
        signedIn(server) {
            openPreferences()
            waitFor("notifications-test")
            tap("notifications-test")
            waitFor("notifications-test-phones")
            onNodeWithText("Sent to 1 of 2 phone(s). It shows in a few seconds.").assertExists()
            onNodeWithTag("notifications-test-phone-0").assertTextEquals("Phone 1: delivered")
            onNodeWithTag("notifications-test-phone-1").assertTextEquals("Phone 2: refused (bad_token)")
        }
    }

    @Test
    fun aTestWithoutPushOrPhoneSaysWhatToDo() {
        val server = server(features = "\"notifications.v1\",\"notifications.push.test.v1\"").also { it.failures[test] = 409 to "push_off" }
        signedIn(server) {
            openPreferences()
            waitFor("notifications-test")
            tap("notifications-test")
            waitFor("notifications-test-result")
            onNodeWithText("Push notifications are off on this server. An administrator turns them on in the dashboard.").assertExists()
        }
    }

    @Test
    fun aServerWithoutChannelsSaysSo() {
        val server = server()
        server.routes[channels] = """{"channels":[],"settings":[]}"""
        signedIn(server) {
            openPreferences()
            waitFor("notifications-no-channel")
            onNodeWithTag("notifications-switch-push-waiting").assertDoesNotExist()
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
            tap("notifications-switch-push-turn_completed")
            waitUntil(timeoutMillis = 10_000) { runCatching { onNodeWithTag("notifications-switch-push-turn_completed").assertIsOff() }.isSuccess }
            onNodeWithTag("notifications-test").assertDoesNotExist()
        }
        assertTrue(server.clients == 0, "the demo makes no request")
    }
}
