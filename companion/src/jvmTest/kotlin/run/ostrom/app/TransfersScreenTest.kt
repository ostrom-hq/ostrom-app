// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/jvmTest/.../TransfersScreenTest.kt; see NOTICE.
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
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertTrue

/** The transfers on the desktop target: an offer to accept or decline in plain words, and the sheet that gives a session away. */
@OptIn(ExperimentalTestApi::class)
class TransfersScreenTest {
    private val origin = "https://ostrom.example.org"

    private fun session(id: String, status: String, owner: String = "m1", extra: String = "") =
        """{"id":"$id","name":"Mill","owner":"$owner","agent":"claude","model":"claude-opus","status":"$status","desiredStatus":"running","epoch":1,
        "limits":{"idleTimeoutMinutes":30,"maxRunMinutes":240,"restrictedEgress":false},"createdAt":"2026-11-14T09:00:00Z","updatedAt":"2026-11-14T09:01:00Z"$extra}"""

    private fun transfer(id: String, state: String, from: String = "m1", to: String = "m2", extra: String = "") =
        """{"id":"$id","sessionId":"s1abcdef99","fromMemberId":"$from","toMemberId":"$to","state":"$state","initiatedBy":"$from","resume":false,
        "createdAt":"2026-11-14T09:00:00Z","updatedAt":"2026-11-14T09:01:00Z","expiresAt":"2099-11-21T09:00:00Z"$extra}"""

    private val recipients = """{"recipients":[{"id":"m1","login":"ada","administrator":true},{"id":"m2","login":"grace","administrator":false},{"id":"m3","login":"linus","administrator":false}]}"""

    private fun server(vararg features: String) = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":[${features.joinToString(",") { "\"$it\"" }}]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/cloud/sessions" to """{"items":[${session("c1", "running")},${session("c2", "transferring", extra = ""","transferId":"t9"""")}]}""",
            "GET /api/v1/cloud/sessions/c1" to session("c1", "running"),
            "GET /api/v1/cloud/sessions/c2" to session("c2", "transferring", extra = ""","transferId":"t9""""),
            "GET /api/v1/cloud/transfer-recipients" to recipients,
            "GET /api/v1/cloud/transfers?direction=incoming" to """{"items":[${transfer("t1", "offered")}]}""",
            "GET /api/v1/cloud/transfers?direction=outgoing" to """{"items":[${transfer("t2", "failed", from = "m2", to = "m3", extra = ""","failure":{"code":"recipient_no_quota","message":"x"}""")}]}""",
        ),
    )

    private fun signedIn(server: FakeServer, memberId: String, block: DesktopComposeUiTest.() -> Unit) {
        val settings = MapSettings()
        SettingsAccountRepository(settings).save(Account(id = "a1", origin = origin, login = "ada", method = SignInMethod.DEVICE, organizationId = "o1", memberId = memberId))
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

    @Test
    fun anOfferShowsItsActionsAndARefusalIsWordedByItsKey() {
        val server = server("runners.cloud.v1", "runners.cloud.transfer.v1")
        server.failures["POST /api/v1/cloud/transfers/t1/decline"] = 409 to "transfer_not_open"
        server.routes["POST /api/v1/cloud/transfers/t1/accept"] = transfer("t1", "accepted")
        signedIn(server, memberId = "m2") {
            click("tab-sessions")
            click("open-transfers")
            waitFor("transfer-t1")
            waitForText("Offered")
            // The sender and the member are named by login, the member as "you".
            waitForText("ada → you")
            onNodeWithTag("cancel-t1").assertDoesNotExist()
            click("decline-t1")
            waitForText("This transfer was already decided.")
            server.failures.clear()
            click("accept-t1")
            waitUntil(timeoutMillis = 10_000) { "POST /api/v1/cloud/transfers/t1/accept" in server.asked }
            waitForText("In progress")
            gone("accept-t1")
            click("direction-outgoing")
            waitFor("transfer-t2")
            waitForText("Not transferred: they have no usage allowance.")
        }
    }

    @Test
    fun theOwnerGivesASessionToAMemberAndTheOfferIsSent() {
        val server = server("runners.cloud.v1", "runners.cloud.transfer.v1")
        server.routes["POST /api/v1/cloud/sessions/c1/transfer"] = transfer("t5", "offered", to = "m2")
        signedIn(server, memberId = "m1") {
            click("tab-sessions")
            click("cloud-session-c1")
            click("transfer")
            waitFor("recipient-m2")
            // The owner is not among the recipients.
            onNodeWithTag("recipient-m1").assertDoesNotExist()
            waitUntil(timeoutMillis = 10_000) { "GET /api/v1/cloud/transfer-recipients" in server.asked }
            click("recipient-m2")
            click("transfer-resume")
            click("transfer-submit")
            waitFor("transfer-done")
            waitForText("Offer sent. The session stays yours until grace accepts it.")
            val sent = server.bodies["POST /api/v1/cloud/sessions/c1/transfer"].orEmpty()
            assertTrue(""""toMemberId":"m2"""" in sent && """"resume":true""" in sent, sent)
            click("transfer-close")
            gone("transfer-done")
        }
    }

    @Test
    fun theActionsAreOfferedOnlyWhereTheServerAndTheSessionAllowThem() {
        // No transfers announced: neither the list nor the action shows.
        val without = server("runners.cloud.v1")
        signedIn(without, memberId = "m1") {
            click("tab-sessions")
            waitFor("cloud-session-c1")
            onNodeWithTag("open-transfers").assertDoesNotExist()
            click("cloud-session-c1")
            waitFor("cloud-session-overview")
            onNodeWithTag("transfer").assertDoesNotExist()
        }
        // A session of someone else, or already changing owner, is not given away by this member.
        val announced = server("runners.cloud.v1", "runners.cloud.transfer.v1")
        announced.routes["GET /api/v1/cloud/sessions/c1"] = session("c1", "running", owner = "m7")
        signedIn(announced, memberId = "m1") {
            click("tab-sessions")
            waitFor("open-transfers")
            click("cloud-session-c1")
            waitFor("cloud-session-overview")
            onNodeWithTag("transfer").assertDoesNotExist()
            click("back")
            click("cloud-session-c2")
            waitFor("cloud-session-transferring")
            waitForText("This session is changing owner")
            onNodeWithTag("transfer").assertDoesNotExist()
        }
    }
}
