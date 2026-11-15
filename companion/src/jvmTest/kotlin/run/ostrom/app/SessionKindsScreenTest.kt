// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/jvmTest/.../SessionScreenTest.kt and NewSessionSheetTest.kt (kinds, devices); see NOTICE.
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import run.ostrom.app.demo.DemoCloudSessionIds
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import kotlin.test.Test
import kotlin.test.assertTrue

/** Remote and local sessions on the desktop target: where each runs, its device's state, and a session started on an online device. */
@OptIn(ExperimentalTestApi::class)
class SessionKindsScreenTest {
    private val origin = "https://ostrom.example.org"

    private fun session(id: String, name: String, status: String, desired: String = "running", extra: String = "") =
        """{"id":"$id","name":"$name","owner":"m1","agent":"claude","model":"claude-opus","status":"$status","desiredStatus":"$desired","epoch":1,
        "limits":{"idleTimeoutMinutes":30,"maxRunMinutes":240,"restrictedEgress":true},"createdAt":"2026-11-14T09:00:00Z","updatedAt":"2026-11-14T09:01:00Z"$extra}"""

    private val host = """"host":{"deviceId":"d1","deviceName":"Ada's MacBook","platform":"macos","osVersion":"15.1","directoryName":"engine","online":false,"permissionMode":"ask"}"""
    private val cloud = session("c1", "Mill", "running")
    private val offline = session("r1", "Loom", "paused", "running",
        ""","kind":"remote","mode":"conversation",$host,"failure":{"code":"host_offline","message":"The device went offline.","retryable":true}""")
    private val waiting = session("r2", "Gears", "creating", extra = ""","kind":"remote","phase":"waiting_host",$host""")
    private val refused = session("r3", "Cards", "failed", extra = ""","kind":"remote",$host,"failure":{"code":"host_refused","message":"The device refused the session (folder_missing).","retryable":false}""")
    private val local = session("l1", "Tables", "running", extra = ""","kind":"local","mode":"conversation"""")

    private fun server(vararg features: String) = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":[${features.joinToString(",") { "\"$it\"" }}]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/activity" to """{"items":[]}""",
            "GET /api/v1/sessions" to """{"items":[]}""",
            "GET /api/v1/cloud/sessions" to """{"items":[$cloud,$offline,$waiting,$refused,$local]}""",
            "GET /api/v1/cloud/sessions/c1" to cloud,
            "GET /api/v1/cloud/sessions/r1" to offline,
            "GET /api/v1/cloud/sessions/r2" to waiting,
            "GET /api/v1/cloud/sessions/r3" to refused,
            "GET /api/v1/cloud/sessions/l1" to local,
            "GET /api/v1/cloud/sessions/r1/events" to """{"events":[],"lastSeq":0}""",
            "GET /api/v1/cloud/sessions/r1/events/stream" to "event: end\ndata: {}\n\n",
            "GET /api/v1/cloud/sessions/l1/events" to """{"events":[],"lastSeq":0}""",
            "GET /api/v1/cloud/sessions/l1/events/stream" to "event: end\ndata: {}\n\n",
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

    private fun DesktopComposeUiTest.openList() {
        click("tab-sessions")
        waitFor("cloud-session-c1")
    }

    @Test
    fun theListSaysWhereEachSessionRunsAndItsDevicesState() {
        val server = server("runners.cloud.v1", "runners.cloud.remote.v1", "runners.cloud.local.v1")
        signedIn(server) {
            openList()
            assertTrue(server.asked.any { it.startsWith("GET /api/v1/cloud/sessions") })
            waitFor("where-c1")
            waitForText("Remote · Ada's MacBook")
            waitFor("offline-r1")
            waitFor("waiting-host-r2")
            waitFor("host-refused-r3")
            waitFor("where-l1")
        }
    }

    @Test
    fun aRemoteSessionShowsItsDeviceOfflineWaitingOrRefused() {
        val server = server("runners.cloud.v1", "runners.cloud.remote.v1", "runners.cloud.conversation.v1")
        signedIn(server) {
            openList()
            click("cloud-session-r1")
            waitForText("Paused on Ada's MacBook. A message asks it to resume.")
            onNodeWithTag("dock-resume").assertDoesNotExist()
            // The record, with the device's banner, is in the conversation's summary.
            click("summary-toggle")
            waitFor("host-offline")
            waitForText("Ada's MacBook stopped answering, so the session paused.")
            click("back")
            click("cloud-session-r2")
            waitFor("waiting-host")
            waitForText("Waiting for Ada's MacBook to start the session.")
            click("back")
            click("cloud-session-r3")
            waitFor("host-refused")
            waitForText("Ada's MacBook refused to start this session: the folder no longer exists on the device.")
        }
    }

    @Test
    fun aLocalSessionIsTalkedToInItsTerminal() {
        val server = server("runners.cloud.v1", "runners.cloud.remote.v1", "runners.cloud.local.v1", "runners.cloud.conversation.v1")
        signedIn(server) {
            openList()
            click("cloud-session-l1")
            waitForText("This session runs in a terminal (ostrom <agent>): talk to it there.")
        }
    }

    @Test
    fun aSessionIsStartedOnTheOnlyOnlineDeviceInItsOnlyFolder() {
        val server = server("runners.cloud.v1", "runners.cloud.remote.v1", "runners.cloud.remote.daemon.v1")
        val daemon = """"daemon":{"online":true,"lastSeenAt":"2026-11-14T09:00:00Z"}"""
        server.routes["GET /api/v1/devices"] = """{"items":[{"id":"d1","name":"Ada's MacBook","platform":"macos","osVersion":"15.1",$daemon},
            {"id":"d2","name":"Old box","platform":"linux"}]}"""
        server.routes["GET /api/v1/devices/d1/folders"] = """{"deviceId":"d1",$daemon,"folders":[{"id":"f1","name":"engine","label":"~/git/engine"}]}"""
        val created = session("r9", "engine", "creating", extra = ""","kind":"remote","mode":"conversation","phase":"waiting_host",$host""")
        server.routes["POST /api/v1/cloud/sessions"] = created
        server.routes["GET /api/v1/cloud/sessions/r9"] = created
        server.routes["GET /api/v1/cloud/sessions/r9/events"] = """{"events":[],"lastSeq":0}"""
        server.routes["GET /api/v1/cloud/sessions/r9/events/stream"] = "event: end\ndata: {}\n\n"
        signedIn(server) {
            openList()
            click("cloud-sessions-new")
            click("target-device")
            waitFor("device-d1")
            onNodeWithTag("device-d2").assertDoesNotExist()
            waitFor("folder-f1")
            click("permissions-bypass")
            waitFor("bypass-warning")
            waitForText("Start on Ada's MacBook")
            click("cloud-new-submit")
            waitUntil(timeoutMillis = 10_000) { "POST /api/v1/cloud/sessions" in server.asked }
            val sent = server.bodies["POST /api/v1/cloud/sessions"].orEmpty()
            listOf(""""kind":"remote"""", """"deviceId":"d1"""", """"folderId":"f1"""", """"permissionMode":"bypass"""", """"name":"engine"""")
                .forEach { assertTrue(it in sent, "$it in $sent") }
        }
    }

    @Test
    fun theDemoShowsARemoteAndALocalSession() {
        val server = server("runners.cloud.v1")
        testApp(server) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            click("tab-sessions")
            click("sessions-kind-cloud")
            waitFor("cloud-sessions")
            onNodeWithTag("cloud-sessions").performScrollToNode(hasTestTag("cloud-session-${DemoCloudSessionIds.REMOTE}"))
            waitFor("where-${DemoCloudSessionIds.REMOTE}")
            waitForText("Remote · Ada's MacBook")
            onNodeWithTag("cloud-sessions").performScrollToNode(hasTestTag("cloud-session-${DemoCloudSessionIds.LOCAL}"))
            waitFor("where-${DemoCloudSessionIds.LOCAL}")
        }
        assertTrue(server.clients == 0, "the demo makes no request")
    }
}
