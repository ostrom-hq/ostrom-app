// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/jvmTest/.../ArtifactsScreenTest.kt; see NOTICE.
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import run.ostrom.app.demo.DemoArtifactIds
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Artifacts tab and the viewer on the desktop target: the gallery paged and searched, the console page, failures, the demo. */
@OptIn(ExperimentalTestApi::class)
class ArtifactsScreenTest {
    private val origin = "https://ostrom.example.org"
    private val first = "00000000-0000-4000-8000-000000000001"
    private val second = "00000000-0000-4000-8000-000000000002"

    private fun artifact(id: String, title: String) =
        """{"id":"$id","title":"$title","owner":"ada","access":"team","latestVersion":2,"url":"https://app.example.org/artifacts/$id",
           "createdAt":"2026-11-12T10:00:00Z","updatedAt":"2026-11-13T10:00:00Z"}"""

    private fun server(features: String = "\"artifacts.v1\"") = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":[$features]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            "GET /api/v1/artifacts?before=c2" to """{"items":[${artifact(first, "Billing plan")},${artifact(second, "Release notes")}]}""",
            "GET /api/v1/artifacts" to """{"items":[${artifact(first, "Billing plan")}],"next":"c2"}""",
            "GET /api/v1/artifacts/$first" to artifact(first, "Billing plan"),
            "GET /api/v1/artifacts/$first/versions" to """{"items":[
                {"number":2,"files":["index.html"],"entry":"index.html","createdAt":"2026-11-13T10:00:00Z","createdBy":"ada"},
                {"number":1,"files":["index.html"],"entry":"index.html","createdAt":"2026-11-12T10:00:00Z","createdBy":"ada"}]}""",
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

    private fun DesktopComposeUiTest.waitForText(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.openTab() {
        waitFor("tab-artifacts")
        onNodeWithTag("tab-artifacts").performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun theGalleryReadsItsOlderPagesAndSearchesWhatIsLoaded() {
        val server = server()
        signedIn(server) {
            openTab()
            waitFor("artifact-$first")
            // The end of a short page is on screen: the older page loads by itself, and the first row is not repeated.
            waitFor("artifact-$second")
            waitForText("v2 · ada")
            assertEquals(1, onAllNodes(hasTestTag("artifact-$first")).fetchSemanticsNodes().size)
            onNodeWithTag("artifacts-search").performTextInput("release")
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("artifact-$first")).fetchSemanticsNodes().isEmpty() }
            onNodeWithTag("artifact-$second").assertExists()
            onNodeWithTag("artifacts-search").performTextInput("zzz")
            waitFor("artifacts-no-match")
        }
        assertTrue("GET /api/v1/artifacts" in server.asked)
    }

    @Test
    fun anArtifactOpensItsConsolePageInTheBrowserOnTheDesktop() {
        val server = server()
        val links = RecordingUriHandler()
        signedIn(server, links) {
            openTab()
            waitFor("artifact-$first")
            onNodeWithTag("artifact-$first").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("artifact-open-desktop")
            waitForText("Version 2")
            onNodeWithTag("artifact-open-desktop").performSemanticsAction(SemanticsActions.OnClick)
            // An older version is chosen from the sheet; the desktop asks no ticket for it.
            onNodeWithTag("artifact-versions").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("artifact-version-1")
            onNodeWithTag("artifact-version-1").performSemanticsAction(SemanticsActions.OnClick)
            waitForText("Version 1")
            onNodeWithTag("back").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("artifacts")
        }
        assertEquals(listOf("https://app.example.org/artifacts/$first"), links.opened)
        assertTrue(server.asked.none { it.endsWith("/tickets") }, server.asked.toString())
    }

    @Test
    fun aFailedGalleryIsRetriedAndAServerWithoutArtifactsHasNoTab() {
        val server = server().also { it.failures["GET /api/v1/artifacts"] = 503 to "unavailable" }
        signedIn(server) {
            openTab()
            waitFor("artifacts-error")
            server.failures.clear()
            onNodeWithTag("artifacts-retry").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("artifact-$first")
        }
        val bare = server(features = "")
        signedIn(bare) {
            waitFor("activity")
            onNodeWithTag("tab-artifacts").assertDoesNotExist()
        }
        assertTrue(bare.asked.none { it.startsWith("GET /api/v1/artifacts") }, bare.asked.toString())
    }

    @Test
    fun aGoneArtifactSaysSoWithoutRetry() {
        val server = server().also { it.routes.remove("GET /api/v1/artifacts/$first") }
        signedIn(server) {
            openTab()
            waitFor("artifact-$first")
            onNodeWithTag("artifact-$first").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("artifact-error")
            onNodeWithTag("artifact-retry").assertDoesNotExist()
        }
    }

    @Test
    fun theDemoShowsItsArtifactsFromMemory() {
        val server = server()
        testApp(server) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            openTab()
            waitFor("artifact-${DemoArtifactIds.PLAN}")
            onNodeWithTag("artifact-${DemoArtifactIds.ROOT_CAUSE}").assertExists()
            onNodeWithTag("artifact-${DemoArtifactIds.PLAN}").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("artifact-desktop")
            // The demo's pages have no console page: nothing to open or copy outside the app.
            waitForText("This artifact shows in the app on a phone.")
            onNodeWithTag("artifact-browser").assertDoesNotExist()
            onNodeWithTag("artifact-versions").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("artifact-version-1")
        }
        assertTrue(server.clients == 0, "the demo makes no request")
    }
}
