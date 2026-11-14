// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/jvmTest/.../SessionScreenTest.kt and NewSessionSheetTest.kt; see NOTICE.
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import run.ostrom.app.demo.DemoCloudSessionIds
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The cloud sessions on the desktop target: the list, a record with its phase and failure, pause, resume, stop, the new session form, the demo. */
@OptIn(ExperimentalTestApi::class)
class CloudSessionsScreenTest {
    private val origin = "https://ostrom.example.org"

    private fun session(id: String, name: String, status: String, desired: String = "running", extra: String = "") =
        """{"id":"$id","name":"$name","owner":"m1","agent":"claude","model":"claude-opus","status":"$status","desiredStatus":"$desired","epoch":1,
        "limits":{"idleTimeoutMinutes":30,"maxRunMinutes":240,"restrictedEgress":true},"createdAt":"2026-11-14T09:00:00Z","updatedAt":"2026-11-14T09:01:00Z"$extra}"""

    private val running = session("c1", "Mill", "running", extra = ""","repo":"https://github.com/acme/api","ref":"main"""")
    private val creating = session("c2", "Cards", "creating", extra = ""","phase":"cloning_repositories"""")
    private val retryable = session("c3", "Notes", "paused", "paused",
        ""","failure":{"code":"preparation_failed","message":"Cloning repository 1 failed (exit code 128).","retryable":true}""")
    private val broken = session("c4", "Tables", "failed", "running",
        ""","failure":{"code":"preparation_failed","message":"Setup command 1 failed (exit code 1).","retryable":false}""")

    private fun server(vararg features: String) = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":[${features.joinToString(",") { "\"$it\"" }}]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            "GET /api/v1/sessions" to """{"items":[]}""",
            "GET /api/v1/cloud/sessions" to """{"items":[$running,$creating,$retryable,$broken]}""",
            "GET /api/v1/cloud/sessions/c1" to running,
            "GET /api/v1/cloud/sessions/c2" to creating,
            "GET /api/v1/cloud/sessions/c3" to retryable,
            "GET /api/v1/cloud/sessions/c4" to broken,
        ),
    )

    private fun signedIn(server: FakeServer, block: DesktopComposeUiTest.() -> Unit) {
        val settings = MapSettings()
        SettingsAccountRepository(settings).save(Account(id = "a1", origin = origin, login = "ada", method = SignInMethod.DEVICE))
        val credentials = MemoryCredentialStore().also { runBlocking { it.write("a1", "osm_token") } }
        testApp(server, credentials = credentials, settings = settings) { block() }
    }

    private fun DesktopComposeUiTest.waitFor(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.waitForText(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.gone(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }

    private fun DesktopComposeUiTest.click(tag: String) {
        waitFor(tag)
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun DesktopComposeUiTest.openCloud() {
        click("tab-sessions")
        click("sessions-kind-cloud")
        waitFor("cloud-session-c1")
    }

    @Test
    fun theCloudSessionsAreListedBesideTheAgentSessionsAndARecordShowsItsPhase() {
        val server = server("sessions.v1", "runners.cloud.v1")
        signedIn(server) {
            click("tab-sessions")
            waitFor("sessions-kind-agent")
            click("sessions-kind-cloud")
            waitFor("cloud-session-c4")
            waitForText("Cloning the repositories")
            waitForText("acme/api")
            click("cloud-session-c2")
            waitFor("cloud-session-overview")
            waitFor("cloud-session-phase")
            waitForText("Preparing: Cloning the repositories")
            waitForText("Pauses after 30 min without activity, runs up to 240 min per start")
            waitForText("Its transcript and terminal are not served to the app.")
            click("back")
            click("cloud-session-c1")
            waitForText("https://github.com/acme/api at main")
            onNodeWithTag("cloud-session-phase").assertDoesNotExist()
        }
    }

    @Test
    fun aFailedPreparationSaysWhetherAResumeMayPass() {
        val server = server("sessions.v1", "runners.cloud.v1")
        signedIn(server) {
            openCloud()
            click("cloud-session-c3")
            waitFor("cloud-session-failure")
            waitForText("Cloning repository 1 failed (exit code 128). Resume to try again.")
            waitFor("cloud-session-resume")
            click("back")
            click("cloud-session-c4")
            waitFor("cloud-session-failure")
            waitForText("must be fixed in the console")
            onNodeWithTag("cloud-session-resume").assertDoesNotExist()
            onNodeWithTag("cloud-session-stop").assertDoesNotExist()
            // A clone that may pass on a session that failed for good: it is started anew, not resumed.
            server.routes["GET /api/v1/cloud/sessions/c4"] = session("c4", "Tables", "failed", "running",
                ""","failure":{"code":"preparation_failed","message":"Cloning repository 1 failed.","retryable":true}""")
            click("cloud-session-refresh")
            waitForText("Start a new session to try again.")
        }
    }

    @Test
    fun aSessionIsPausedResumedAndStoppedEachReadAgainAfter() {
        val server = server("sessions.v1", "runners.cloud.v1")
        val paused = session("c1", "Mill", "paused", "paused")
        server.routes["POST /api/v1/cloud/sessions/c1/pause"] = session("c1", "Mill", "pausing", "paused")
        server.routes["POST /api/v1/cloud/sessions/c1/resume"] = session("c1", "Mill", "resuming")
        server.routes["DELETE /api/v1/cloud/sessions/c1"] = session("c1", "Mill", "running", "terminated")
        signedIn(server) {
            openCloud()
            click("cloud-session-c1")
            waitFor("cloud-session-pause")
            onNodeWithTag("cloud-session-resume").assertDoesNotExist()
            server.routes["GET /api/v1/cloud/sessions/c1"] = paused
            click("cloud-session-pause")
            waitUntil(timeoutMillis = 10_000) { "POST /api/v1/cloud/sessions/c1/pause" in server.asked }
            waitFor("cloud-session-resume")
            gone("cloud-session-pause")
            // A refusal is worded by its key, never with the server's text.
            server.failures["POST /api/v1/cloud/sessions/c1/resume"] = 402 to "payment_required"
            click("cloud-session-resume")
            waitFor("cloud-session-action-error")
            waitForText("Your plan's cloud sessions at once, or its hours of this period, are used up.")
            server.failures.clear()
            server.routes["GET /api/v1/cloud/sessions/c1"] = session("c1", "Mill", "running")
            click("cloud-session-resume")
            waitFor("cloud-session-pause")
            click("cloud-session-stop")
            waitFor("cloud-stop-confirm")
            click("cloud-stop-keep")
            gone("cloud-stop-confirm")
            assertFalse("DELETE /api/v1/cloud/sessions/c1" in server.asked)
            server.routes["GET /api/v1/cloud/sessions/c1"] = session("c1", "Mill", "terminated", "terminated", ""","endedAt":"2026-11-14T10:00:00Z"""")
            click("cloud-session-stop")
            click("cloud-stop-yes")
            waitUntil(timeoutMillis = 10_000) { "DELETE /api/v1/cloud/sessions/c1" in server.asked }
            waitForText("Stopped")
            gone("cloud-session-stop")
        }
    }

    @Test
    fun withoutTheEnvironmentFeatureTheFormTakesTheSingleRepository() {
        // Cloud sessions alone: the Sessions tab shows them without a switch.
        val server = server("runners.cloud.v1")
        server.routes["POST /api/v1/cloud/sessions"] = session("c9", "api", "creating", extra = ""","phase":"configuring_git"""")
        server.routes["GET /api/v1/cloud/sessions/c9"] = session("c9", "api", "creating", extra = ""","phase":"configuring_git"""")
        signedIn(server) {
            click("tab-sessions")
            waitFor("cloud-session-c1")
            onNodeWithTag("sessions-kind-cloud").assertDoesNotExist()
            click("cloud-sessions-new")
            waitFor("cloud-new-repo")
            onNodeWithTag("cloud-new-repository-url-0").assertDoesNotExist()
            onNodeWithTag("cloud-new-repo").performTextInput("https://gitlab.com/acme/api")
            click("cloud-new-submit")
            waitForText("Use an https://github.com/<owner>/<name> repository address.")
            assertFalse("POST /api/v1/cloud/sessions" in server.asked)
            onNodeWithTag("cloud-new-repo").performTextReplacement("https://github.com/acme/api")
            onNodeWithTag("cloud-new-ref").performTextInput("main")
            click("cloud-new-agent-codex")
            click("cloud-new-submit")
            waitFor("cloud-session-overview")
            waitForText("Preparing: Configuring Git")
            val sent = server.bodies["POST /api/v1/cloud/sessions"].orEmpty()
            assertTrue(""""agent":"codex"""" in sent && """"repo":"https://github.com/acme/api"""" in sent && """"ref":"main"""" in sent, sent)
            assertFalse("environment" in sent, sent)
        }
    }

    @Test
    fun withTheEnvironmentFeatureTheFormTakesTheRecipe() {
        val server = server("sessions.v1", "runners.cloud.v1", "runners.cloud.environment.v1")
        server.routes["POST /api/v1/cloud/sessions"] = session("c9", "api", "creating")
        server.routes["GET /api/v1/cloud/sessions/c9"] = session("c9", "api", "creating")
        signedIn(server) {
            openCloud()
            click("cloud-sessions-new")
            waitFor("cloud-new-repository-url-0")
            onNodeWithTag("cloud-new-repo").assertDoesNotExist()
            onNodeWithTag("cloud-new-repository-url-0").performTextInput("https://github.com/acme/api")
            onNodeWithTag("cloud-new-repository-path-0").performTextInput("api")
            click("cloud-new-repository-add")
            onNodeWithTag("cloud-new-repository-url-1").performTextInput("https://github.com/acme/web")
            onNodeWithTag("cloud-new-repository-branch-1").performTextInput("feature")
            onNodeWithTag("cloud-new-working-directory").performTextInput("api")
            onNodeWithTag("cloud-new-setup-create").performTextInput("npm ci")
            onNodeWithTag("cloud-new-setup-start").performTextInput("make dev")
            onNodeWithTag("cloud-new-variables").performTextInput("NPM_TOKEN, API_KEY")
            click("cloud-new-require-github")
            onNodeWithTag("cloud-new-email-domain").performTextInput("acme.dev")
            onNodeWithTag("cloud-new-prompt").performTextInput("Fix the build")
            click("cloud-new-submit")
            waitFor("cloud-session-overview")
            val sent = server.bodies["POST /api/v1/cloud/sessions"].orEmpty()
            listOf(
                """"repositories":[{"url":"https://github.com/acme/api","path":"api"},{"url":"https://github.com/acme/web","branch":"feature"}]""",
                """"workingDirectory":"api"""", """"setup":{"create":["npm ci"],"start":["make dev"]}""", """"requiredVariables":["NPM_TOKEN","API_KEY"]""",
                """"requireGitHub":true""", """"gitEmailDomain":"acme.dev"""", """"prompt":"Fix the build"""",
            ).forEach { assertTrue(it in sent, "$it in $sent") }
            assertFalse(""""repo":""" in sent, sent)
        }
    }

    @Test
    fun theDemoKeepsItsCloudSessionsInMemory() {
        val server = server("runners.cloud.v1")
        testApp(server) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            click("tab-sessions")
            click("sessions-kind-cloud")
            waitFor("cloud-session-${DemoCloudSessionIds.PREPARING}")
            waitForText("Running the setup commands")
            click("cloud-session-${DemoCloudSessionIds.RETRYABLE}")
            waitForText("Resume to try again.")
            click("cloud-session-resume")
            waitFor("cloud-session-pause")
            gone("cloud-session-failure")
            click("back")
            click("cloud-session-${DemoCloudSessionIds.RUNNING}")
            waitFor("cloud-session-recipe")
            click("cloud-session-stop")
            click("cloud-stop-yes")
            waitForText("Stopped")
            click("back")
            click("cloud-sessions-new")
            waitFor("cloud-new-repository-url-0")
            onNodeWithTag("cloud-new-repository-url-0").performTextInput("https://github.com/analytical-engines/mill")
            click("cloud-new-submit")
            waitFor("cloud-session-overview")
            waitForText("mill")
        }
        assertTrue(server.clients == 0, "the demo makes no request")
    }
}
