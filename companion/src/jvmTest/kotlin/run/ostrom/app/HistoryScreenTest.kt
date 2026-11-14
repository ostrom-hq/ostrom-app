// SPDX-License-Identifier: Apache-2.0
// Ported from Ostrom's React Native app, src/ui/screens/history/HistoryScreen.test.tsx, HistoryAttestation.test.tsx and
// HistoryCheckOnShow.test.tsx.
package run.ostrom.app

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.infrastructure.credentials.SecureReceiptKeyPinRepository
import run.ostrom.app.infrastructure.settings.SettingsAccountRepository
import run.ostrom.core.receipts.ReceiptSignerImpl
import run.ostrom.core.receipts.ReceiptSigningKey
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The History tab of a signed-in account: receipts verified on the device, the key pinned, failures, paging, the demo. */
@OptIn(ExperimentalTestApi::class)
class HistoryScreenTest {
    private val origin = "https://ostrom.example.org"
    private val key = ReceiptSigningKey.generate()

    private fun body(id: String, result: String? = "approved", by: String = "ada", ci: Boolean = false) = buildJsonObject {
        put("id", id)
        putJsonObject("subject") { put("kind", if (ci) "workflow" else "gate"); put("id", "subject-$id"); put("digest", "ab".repeat(32)) }
        put("initiator", "charles")
        put("rules", buildJsonArray {})
        putJsonArray("decisions") {
            if (result != null) add(buildJsonObject { put("approvalId", "g-$id"); put("by", by); put("at", "2026-11-13T10:00:00Z"); put("result", result) })
        }
        if (ci) putJsonArray("nodes") {
            add(buildJsonObject { put("id", "n1"); put("workflowId", "w"); put("kind", "check"); put("label", "Build"); put("outcome", "passed"); put("attestation", "ci") })
        }
        put("createdAt", "2026-11-13T09:00:00.000Z")
    }

    private fun signed(id: String, result: String? = "approved", ci: Boolean = false, with: ReceiptSigningKey = key) = ReceiptSignerImpl(with).sign(body(id, result, ci = ci))

    private fun page(vararg items: JsonObject, next: String? = null) = buildJsonObject {
        put("items", buildJsonArray { items.forEach { add(it) } })
        if (next != null) put("next", next)
    }.toString()

    private fun keys(vararg served: ReceiptSigningKey) = buildJsonObject {
        putJsonArray("keys") {
            served.forEach { add(buildJsonObject { put("kid", it.publicKey.kid); put("alg", "Ed25519"); put("publicKeyPem", it.publicKey.toSpkiPem()) }) }
        }
    }.toString()

    private fun server(features: String = "\"receipts.v1\"", receipts: String = page(signed("rcpt_1"))) = FakeServer(
        mutableMapOf(
            "GET /api/v1/capabilities" to """{"apiVersion":1,"serverVersion":"1.4.0","features":[$features]}""",
            "GET /api/v1/auth/me" to """{"dev":{"login":"ada","verified":true,"via":"device"},"capabilities":[]}""",
            "GET /api/v1/receipts" to receipts,
            "GET /api/v1/receipts/keys" to keys(key),
            "GET /api/v1/activity" to """{"items":[]}""",
        ),
    )

    private fun signedIn(server: FakeServer, credentials: MemoryCredentialStore = MemoryCredentialStore(), block: DesktopComposeUiTest.() -> Unit) {
        val settings = MapSettings()
        SettingsAccountRepository(settings).save(Account(id = "a1", origin = origin, login = "ada", method = SignInMethod.DEVICE))
        runBlocking { credentials.write("a1", "osm_token") }
        testApp(server, credentials = credentials, settings = settings) { block() }
    }

    private fun DesktopComposeUiTest.waitFor(tag: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun DesktopComposeUiTest.waitForText(text: String) =
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun eachReceiptIsVerifiedOnTheDeviceAndAChangedOneIsRejected() {
        val workflow = signed("rcpt_w", result = null, ci = true)
        val tampered = JsonObject(signed("rcpt_t", ci = true) + ("initiator" to JsonPrimitive("mallory")))
        val server = server(receipts = page(signed("rcpt_1"), workflow, tampered))
        signedIn(server) {
            waitFor("receipt-rcpt_1")
            waitForText("Signature verified on this device · key ${key.publicKey.kid}")
            waitForText("Approved by ada")
            waitForText("gate subject-rcpt_1")
            // A workflow nobody decided, with its check run by the CI: proven, the receipt being verified.
            waitForText("Signed for charles")
            onNodeWithTag("receipt-rcpt_w-run-0").performScrollTo()
            waitForText("Build · passed")
            waitForText("Run by the CI")
            // Changed after signing: rejected, and its CI claim proves nothing.
            onNodeWithTag("receipt-rcpt_t").performScrollTo()
            waitForText("Signature rejected: the content changed after signing")
            waitForText("Origin not verified")
        }
        assertTrue("GET /api/v1/receipts/keys" in server.asked)
    }

    @Test
    fun theKeyIsPinnedOnFirstViewAndANewKeyIsNotTrustedWithoutARotation() {
        val credentials = MemoryCredentialStore()
        signedIn(server(), credentials) {
            waitFor("check-verified")
        }
        assertNotNull(runBlocking { credentials.read(SecureReceiptKeyPinRepository.entry(origin)) }, "the key is pinned for the server")
        // The server now serves another key beside it, handed over by nobody, and signs with it.
        val other = ReceiptSigningKey.generate()
        val rotated = server(receipts = page(signed("rcpt_2", with = other))).also { it.routes["GET /api/v1/receipts/keys"] = keys(key, other) }
        signedIn(rotated, credentials) {
            waitFor("receipt-rcpt_2")
            waitForText("Signature rejected: signed by a key this device does not trust")
            onNodeWithTag("check-verified").assertDoesNotExist()
        }
    }

    @Test
    fun aReceiptThisAppCannotReadStillHasItsRowAndIsChecked() {
        val server = server(receipts = page(buildJsonObject { put("id", "rcpt_x"); put("shape", "newer") }, signed("rcpt_1")))
        signedIn(server) {
            waitFor("receipt-unreadable-0")
            waitForText("A receipt this version of the app cannot display")
            waitForText("Signature rejected: no signature")
            waitFor("check-verified")
        }
    }

    @Test
    fun withoutAKeyNothingIsVerified() {
        val server = server().also { it.failures["GET /api/v1/receipts/keys"] = 503 to "unavailable" }
        signedIn(server) {
            waitFor("receipt-rcpt_1")
            waitForText("Not verified: no trusted key of this server")
            onNodeWithTag("check-verified").assertDoesNotExist()
        }
    }

    @Test
    fun aFailedRefreshKeepsTheReceiptsUnderTheStaleBanner() {
        val server = server()
        signedIn(server) {
            waitFor("check-verified")
            server.failures["GET /api/v1/receipts"] = 503 to "unavailable"
            // Back to the tab: it reads its first page again.
            onNodeWithTag("tab-activity").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("activity")
            onNodeWithTag("tab-history").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("history-stale")
            waitForText("The receipts could not be refreshed: the list may be out of date. Pull to try again.")
            onNodeWithTag("receipt-rcpt_1").assertExists()
            server.failures.clear()
            onNodeWithTag("history-retry").performSemanticsAction(SemanticsActions.OnClick)
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("history-stale")).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test
    fun olderReceiptsArePagedAndVerifiedToo() {
        val server = server(receipts = page(signed("rcpt_new"), next = "c2"))
        server.routes["GET /api/v1/receipts?before=c2"] = page(signed("rcpt_new"), signed("rcpt_old", result = "rejected"))
        signedIn(server) {
            waitFor("receipt-rcpt_new")
            // The end of a short page is on screen: the older page loads by itself, without repeating rcpt_new.
            waitFor("receipt-rcpt_old")
            waitForText("Declined by ada")
            waitUntil(timeoutMillis = 10_000) { onAllNodes(hasTestTag("check-verified")).fetchSemanticsNodes().size == 2 }
            onNodeWithTag("history-more").assertDoesNotExist()
        }
    }

    @Test
    fun anEmptyHistoryAFailureRetriedAndNoTabWithoutReceipts() {
        signedIn(server(receipts = page())) {
            waitFor("history-empty")
            waitForText("No receipt yet")
        }
        val failing = server().also { it.failures["GET /api/v1/receipts"] = 503 to "unavailable" }
        signedIn(failing) {
            waitFor("history-error")
            failing.failures.clear()
            onNodeWithTag("history-retry").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("check-verified")
        }
        val bare = server(features = "")
        signedIn(bare) {
            waitFor("activity")
            onNodeWithTag("tab-history").assertDoesNotExist()
        }
        assertTrue(bare.asked.none { it.startsWith("GET /api/v1/receipts") }, bare.asked.toString())
    }

    @Test
    fun theDemoSignsItsReceiptsAndTheyAreVerified() {
        val server = server()
        testApp(server) {
            onNodeWithTag("try-demo").performSemanticsAction(SemanticsActions.OnClick)
            settle()
            waitFor("tab-history")
            onNodeWithTag("tab-history").performSemanticsAction(SemanticsActions.OnClick)
            waitFor("receipt-rcpt_demo-merge")
            waitForText("Signature verified on this device")
            onNodeWithTag("check-no-key").assertDoesNotExist()
            onNodeWithTag("check-rejected").assertDoesNotExist()
            onNodeWithTag("receipt-rcpt_demo-release").performScrollTo()
            waitForText("Run by the CI")
            waitForText("Declared by the workstation")
        }
        assertTrue(server.clients == 0, "the demo makes no request")
    }
}
