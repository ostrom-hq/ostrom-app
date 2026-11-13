// SPDX-License-Identifier: AGPL-3.0-only
// Shaped like TeamCode's companion jvmTest WaitsScreenTest.kt (Apache-2.0); see NOTICE.
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
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
import kotlin.test.assertTrue

/** The Spending and Activity tabs of a signed-in account: the report by period, passed budgets, the feed paged, failures, the demo. */
@OptIn(ExperimentalTestApi::class)
class SpendingScreenTest {
    private val origin = "https://ostrom.example.org"

    private fun spending(period: String, total: String) = """
        {"period":"$period","from":"2026-11-01T00:00:00Z","to":"2026-12-01T00:00:00Z","totalUsd":$total,"budgetUsd":500.0,
         "byAgent":[{"key":"codex","label":"Codex","usd":$total,"sessions":9}],"byRepo":[],
         "byPerson":[{"key":"m-charles","label":"charles","usd":$total,"sessions":9}],"daily":[],
         "exceeded":[{"scope":"member","subject":"m-charles","period":"day","usd":25.0,"hardStop":true,"since":"2026-11-13T09:00:00Z"}]}
    """.trimIndent()

    private fun item(id: String, summary: String) =
        """{"id":"$id","at":"2026-11-13T10:00:00Z","kind":"session.ended","actor":{"kind":"agent","name":"Codex"},"summary":"$summary","repo":"ae/web","costUsd":4.12}"""

    private fun server(features: String = "\"budgets.v1\"") = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":[$features]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/spending?period=month" to spending("month", "412.8"),
            "GET /api/v1/spending?period=week" to spending("week", "103.2"),
            "GET /api/v1/activity?before=c2" to """{"items":[${item("i2", "Older session")},${item("i1", "Session ended")}]}""",
            "GET /api/v1/activity" to """{"items":[${item("i1", "Session ended")}],"next":"c2"}""",
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

    private fun DesktopComposeUiTest.waitForText(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun theSpendingOfTheMonthThenOfTheWeekWithTheBudgetsPassed() {
        val server = server()
        signedIn(server) {
            waitFor("tab-spending")
            onNodeWithTag("tab-spending").performSemanticsAction(SemanticsActions.OnClick)
            waitForText("$412.80")
            onNodeWithTag("spending-budget").assertExists()
            onNodeWithText("83 % of the $500.00 budget", substring = true).assertExists()
            onNodeWithTag("spending-exceeded").performScrollTo()
            onNodeWithText("charles is past their daily budget of $25.00", substring = true).assertExists()
            onNodeWithText("The model calls are refused until the day ends.").assertExists()
            onNodeWithTag("spending-agent").performScrollTo()
            onNodeWithText("Codex").assertExists()

            onNodeWithTag("spending-period-week").performSemanticsAction(SemanticsActions.OnClick)
            waitForText("$103.20")
        }
        assertTrue("GET /api/v1/spending" in server.asked)
    }

    @Test
    fun aFailedSpendingIsRetriedAndAServerWithoutBudgetsHasNoTab() {
        val server = server().also { it.failures["GET /api/v1/spending"] = 503 to "unavailable" }
        signedIn(server) {
            waitFor("tab-spending")
            onNodeWithTag("tab-spending").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("spending-error-state")
            server.failures.clear()
            onNodeWithTag("spending-retry").performSemanticsAction(SemanticsActions.OnClick)
            waitForText("$412.80")
        }
        val bare = server(features = "")
        signedIn(bare) {
            waitFor("activity")
            onNodeWithTag("tab-spending").assertDoesNotExist()
        }
        assertTrue(bare.asked.none { it.startsWith("GET /api/v1/spending") }, bare.asked.toString())
    }

    @Test
    fun theActivityIsReadNewestFirstAndOlderPagesAddOnlyNewLines() {
        val server = server(features = "")
        signedIn(server) {
            waitFor("activity-row-i1")
            onNodeWithText("Session ended").assertExists()
            // The details line is a string resource, read once its strings are loaded.
            waitForText("Agent Codex · ae/web · $4.12")
            onNodeWithTag("activity-more").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            waitFor("activity-row-i2")
            onNodeWithTag("activity-more").assertDoesNotExist()
        }
    }

    @Test
    fun aFailedActivityIsRetried() {
        val server = server(features = "").also { it.failures["GET /api/v1/activity"] = 503 to "unavailable" }
        signedIn(server) {
            waitFor("activity-error-state")
            server.failures.clear()
            onNodeWithTag("activity-retry").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("activity-row-i1")
        }
    }

    @Test
    fun theDemoShowsItsSpendingAndActivityFromMemory() {
        val server = server()
        testApp(server) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            waitFor("tab-spending")
            onNodeWithTag("tab-spending").performSemanticsAction(SemanticsActions.OnClick)
            waitForText("$412.80")
            onNodeWithTag("spending-exceeded").performScrollTo()
            onNodeWithTag("tab-activity").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("activity-row-act-1")
        }
        assertTrue(server.clients == 0, "the demo makes no request")
    }
}
