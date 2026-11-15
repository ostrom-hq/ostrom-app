// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/jvmTest/.../SessionScreenTest.kt; see NOTICE.
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
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A conversation session on the desktop target: its transcript, the composer, Interrupt and a permission card, through the whole app. */
@OptIn(ExperimentalTestApi::class)
class CloudConversationScreenTest {
    private val origin = "https://ostrom.example.org"

    private fun session(id: String, status: String = "running", extra: String = "") =
        """{"id":"$id","name":"Bernoulli","owner":"m1","agent":"claude","model":"claude-opus","status":"$status","desiredStatus":"running","epoch":1,
        "mode":"conversation","limits":{"idleTimeoutMinutes":30,"maxRunMinutes":240,"restrictedEgress":true},
        "createdAt":"2026-11-14T09:00:00Z","updatedAt":"2026-11-14T09:01:00Z"$extra}"""

    private fun event(seq: Long, type: String, data: String) = """{"seq":$seq,"type":"$type","at":"2026-11-14T09:0$seq:00Z","epoch":1,"data":$data}"""

    private val events = listOf(
        event(1, "message.user", """{"text":"Compute B8","inputId":"in-0","authorId":"m1"}"""),
        event(2, "turn", """{"phase":"started"}"""),
        event(3, "message.agent", """{"text":"B8 is -1/30."}"""),
    )

    private fun input(id: String, kind: String) = """{"id":"$id","kind":"$kind","status":"queued","authorId":"m1","createdAt":"2026-11-14T10:00:00Z"}"""

    private fun server(vararg features: String, page: List<String> = events) = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":[${features.joinToString(",") { "\"$it\"" }}]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            "GET /api/v1/cloud/sessions" to """{"items":[${session("c5")}]}""",
            "GET /api/v1/cloud/sessions/c5" to session("c5"),
            "GET /api/v1/cloud/sessions/c5/events" to """{"events":[${page.joinToString(",")}],"lastSeq":${page.size}}""",
            // The stream sends nothing new, then the session ends: nothing to reconnect to.
            "GET /api/v1/cloud/sessions/c5/events/stream" to "event: end\ndata: {}\n\n",
            "POST /api/v1/cloud/sessions/c5/messages" to input("in-1", "message"),
            "POST /api/v1/cloud/sessions/c5/interrupt" to input("int-1", "interrupt"),
            "POST /api/v1/cloud/sessions/c5/permissions" to input("ans-1", "permission_answer"),
        ),
    )

    private fun signedIn(server: FakeServer, block: DesktopComposeUiTest.() -> Unit) {
        val settings = MapSettings()
        SettingsAccountRepository(settings).save(Account(id = "a1", origin = origin, login = "ada", method = SignInMethod.DEVICE, memberId = "m1"))
        val credentials = MemoryCredentialStore().also { runBlocking { it.write("a1", "osm_token") } }
        testApp(server, credentials = credentials, settings = settings) { block() }
    }

    private fun DesktopComposeUiTest.waitFor(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.waitForText(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.click(tag: String) {
        waitFor(tag)
        onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun DesktopComposeUiTest.open() {
        click("tab-sessions")
        click("cloud-session-c5")
    }

    @Test
    fun theTranscriptShowsAndAMessageAndAnInterruptAreSent() {
        val server = server("runners.cloud.v1", "runners.cloud.conversation.v1")
        signedIn(server) {
            open()
            waitFor("conversation")
            waitForText("Compute B8")
            waitForText("B8 is -1/30.")
            assertTrue(server.asked.any { it == "GET /api/v1/cloud/sessions/c5/events" })
            // The record stays one tap away.
            onNodeWithTag("cloud-session-overview").assertDoesNotExist()
            click("summary-toggle")
            waitFor("cloud-session-overview")

            onNodeWithTag("composer").performTextInput("And B10?")
            click("send")
            waitUntil(timeoutMillis = 10_000) { "POST /api/v1/cloud/sessions/c5/messages" in server.asked }
            val sent = server.bodies["POST /api/v1/cloud/sessions/c5/messages"].orEmpty()
            assertTrue(""""text":"And B10?"""" in sent, sent)

            click("interrupt")
            waitUntil(timeoutMillis = 10_000) { "POST /api/v1/cloud/sessions/c5/interrupt" in server.asked }
        }
    }

    @Test
    fun aPermissionRequestIsAnsweredFromItsCard() {
        val request = event(4, "permission.request", """{"requestId":"perm-1","toolName":"Bash","input":{"command":"git push"}}""")
        val server = server("runners.cloud.v1", "runners.cloud.conversation.v1", "runners.cloud.permissions.v1", page = events + request)
        signedIn(server) {
            open()
            waitFor("permission-card")
            waitForText("git push")
            click("permission-allow")
            waitUntil(timeoutMillis = 10_000) { "POST /api/v1/cloud/sessions/c5/permissions" in server.asked }
            val sent = server.bodies["POST /api/v1/cloud/sessions/c5/permissions"].orEmpty()
            assertTrue(""""requestId":"perm-1"""" in sent && """"behavior":"allow"""" in sent, sent)
        }
    }

    @Test
    fun withoutTheConversationKeyTheRecordShowsAsBefore() {
        val server = server("runners.cloud.v1")
        signedIn(server) {
            open()
            waitFor("cloud-session-overview")
            onNodeWithTag("conversation").assertDoesNotExist()
            assertFalse(server.asked.any { it.startsWith("GET /api/v1/cloud/sessions/c5/events") })
        }
    }
}
