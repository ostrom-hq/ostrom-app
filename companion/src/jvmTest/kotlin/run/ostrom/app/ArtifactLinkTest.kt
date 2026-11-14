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
import org.koin.core.Koin
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import run.ostrom.app.presentation.navigation.IncomingLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ostrom://open?url=<artifact page>` (TC-COM-29): a signed-in account's artifact opens in the viewer, anything else is refused. */
@OptIn(ExperimentalTestApi::class)
class ArtifactLinkTest {
    private val id = "00000000-0000-4000-8000-000000000001"

    private fun artifact() = """{"id":"$id","title":"Billing plan","owner":"ada","access":"team","latestVersion":2,"url":"https://app.acme.dev/artifacts/$id",
        "createdAt":"2026-11-12T10:00:00Z","updatedAt":"2026-11-13T10:00:00Z"}"""

    private fun server() = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":["artifacts.v1"]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            "GET /api/v1/artifacts" to """{"items":[]}""",
            "GET /api/v1/artifacts/$id" to artifact(),
            "GET /api/v1/artifacts/$id/versions" to """{"items":[{"number":2,"files":["index.html"],"entry":"index.html","createdAt":"2026-11-13T10:00:00Z","createdBy":"ada"}]}""",
        ),
    )

    /** Two accounts: a1 on ostrom.example.org (active), a2 on api.acme.dev, whose console is app.acme.dev. */
    private fun signedIn(server: FakeServer, block: DesktopComposeUiTest.(Koin) -> Unit) {
        val settings = MapSettings()
        val accounts = SettingsAccountRepository(settings)
        accounts.save(Account(id = "a1", origin = "https://ostrom.example.org", login = "ada", method = SignInMethod.DEVICE))
        accounts.save(Account(id = "a2", origin = "https://api.acme.dev", login = "ada", method = SignInMethod.DEVICE))
        accounts.setActive("a1")
        val credentials = MemoryCredentialStore().also { runBlocking { it.write("a1", "osm_one"); it.write("a2", "osm_two") } }
        testApp(server, credentials = credentials, settings = settings) { koin -> block(koin) }
    }

    private fun DesktopComposeUiTest.waitFor(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.waitForText(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun encoded(url: String) = java.net.URLEncoder.encode(url, "UTF-8")

    @Test
    fun anArtifactOfASignedInAccountsConsoleOpensInTheViewerWithThatAccountActive() {
        val server = server()
        signedIn(server) { koin ->
            waitFor("tabs")
            IncomingLinks.open("ostrom://open?url=${encoded("https://app.acme.dev/artifacts/$id?v=2")}")
            waitForText("Billing plan")
            assertEquals(null, IncomingLinks.link.value)
            assertEquals("a2", koin.get<AccountRepository>().activeAccountId.value)
            assertTrue("GET /api/v1/artifacts/$id" in server.asked)
            onNodeWithTag("link-refused").assertDoesNotExist()
            // Back returns to the tabs.
            onNodeWithTag("back").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("tabs")
        }
    }

    @Test
    fun aLinkToAnotherServerOrNotToAnArtifactIsRefusedAndNothingIsOpened() {
        val server = server()
        signedIn(server) { koin ->
            waitFor("tabs")
            IncomingLinks.open("ostrom://open?url=${encoded("https://evil.example/artifacts/$id")}")
            waitFor("link-refused")
            waitForText("none of your accounts is on")
            onNodeWithTag("link-refused-close").performSemanticsAction(SemanticsActions.OnClick)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("link-refused")).fetchSemanticsNodes().isEmpty() }
            IncomingLinks.open("ostrom://open?url=${encoded("https://app.acme.dev/settings")}")
            waitFor("link-refused")
            waitForText("does not lead to an Ostrom artifact")
            assertTrue(server.asked.none { it.startsWith("GET /api/v1/artifacts/") }, server.asked.toString())
            assertEquals("a1", koin.get<AccountRepository>().activeAccountId.value)
        }
    }

    @Test
    fun withoutAnyAccountTheLinkIsRefused() {
        testApp(server()) {
            waitFor("origin")
            IncomingLinks.open("ostrom://open?url=${encoded("https://app.acme.dev/artifacts/$id")}")
            waitFor("link-refused")
            waitForText("none of your accounts is on")
        }
    }
}
