// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.demo

import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import run.ostrom.app.domain.models.ReceiptPage
import run.ostrom.app.domain.models.ServedReceiptKey
import run.ostrom.app.domain.repositories.ReceiptKeyPinRepository
import run.ostrom.app.domain.repositories.ReceiptRepository
import run.ostrom.app.infrastructure.repositories.readReceipt
import run.ostrom.core.api.models.CheckAttestation
import run.ostrom.core.api.models.ReceiptDecision
import run.ostrom.core.api.models.ReceiptDraft
import run.ostrom.core.api.models.ReceiptNode
import run.ostrom.core.api.models.ReceiptPackage
import run.ostrom.core.api.models.ReceiptRule
import run.ostrom.core.api.models.ReceiptSubject
import run.ostrom.core.api.models.ReceiptSubjectKind
import run.ostrom.core.receipts.Digests
import run.ostrom.core.receipts.ReceiptJson
import run.ostrom.core.receipts.ReceiptSigner

private const val MINUTE = 60_000L

private fun iso(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString()

private fun digest(of: String): String = Digests.sha256Hex(of.encodeToByteArray())

/**
 * The demo organization's receipts, newest first, signed by [signer], the demo's own key (generated for each demo, kept
 * in memory): the merge Ada approved, a release workflow with its checks run by the CI, by Ostrom and on a workstation,
 * a hotfix she approved herself, and a task nobody had to decide.
 */
internal fun demoReceipts(startedAt: Long, signer: ReceiptSigner): List<JsonObject> = listOf(
    ReceiptDraft(
        id = "rcpt_demo-merge",
        subject = ReceiptSubject(ReceiptSubjectKind.GATE, "demo-merge", digest("demo-merge")),
        initiator = "charles",
        agent = "claude-code",
        rules = listOf(ReceiptRule("release/merge-to-main", digest("merge-to-main"))),
        decisions = listOf(ReceiptDecision("demo-merge", "ada", iso(startedAt - 90 * MINUTE), "approved")),
        createdAt = iso(startedAt - 92 * MINUTE),
    ),
    ReceiptDraft(
        id = "rcpt_demo-release",
        subject = ReceiptSubject(ReceiptSubjectKind.WORKFLOW, "release@p202611140900-3fa1c2d4", digest("release")),
        initiator = "charles",
        agent = "claude-code",
        packageRef = ReceiptPackage("p202611140900-3fa1c2d4", digest("package")),
        nodes = listOf(
            ReceiptNode("build", "release", "check", "Build", "passed", attestation = CheckAttestation.CI),
            ReceiptNode("tests", "release", "check", "Tests", "passed", attestation = CheckAttestation.ISOLATED_RUNNER),
            ReceiptNode("lint", "release", "check", "Lint", "passed", attestation = CheckAttestation.WORKSTATION_DECLARED),
            ReceiptNode("notify", "release", "notify", "Tell the team", "sent"),
        ),
        createdAt = iso(startedAt - 3 * 60 * MINUTE),
    ),
    ReceiptDraft(
        id = "rcpt_demo-hotfix",
        subject = ReceiptSubject(ReceiptSubjectKind.GATE, "demo-hotfix", digest("demo-hotfix")),
        initiator = "ada",
        agent = "codex",
        decisions = listOf(ReceiptDecision("demo-hotfix", "ada", iso(startedAt - 26 * 60 * MINUTE), "approved", selfApproved = true)),
        createdAt = iso(startedAt - 26 * 60 * MINUTE - 2 * MINUTE),
    ),
    ReceiptDraft(
        id = "rcpt_demo-task",
        subject = ReceiptSubject(ReceiptSubjectKind.TASK, "carry-the-tens", digest("carry-the-tens")),
        initiator = "charles",
        agent = "claude-code",
        createdAt = iso(startedAt - 2 * 24 * 60 * MINUTE),
    ),
).map { draft -> signer.sign(ReceiptJson.encodeToJsonElement(ReceiptDraft.serializer(), draft).jsonObject) }

/** The demo's receipts and their key, from memory: signing and key reading are CPU work, off the main thread. */
class DemoReceiptRepository(private val demo: DemoMode) : ReceiptRepository {
    override suspend fun page(accountId: String, before: String?): ReceiptPage = withContext(Dispatchers.Default) {
        val store = demo.store()
        ReceiptPage(if (before == null) store.receipts.map(::readReceipt) else emptyList(), null)
    }

    override suspend fun keys(accountId: String): List<ServedReceiptKey> = withContext(Dispatchers.Default) {
        val key = demo.store().receiptSigner.publicKey
        listOf(ServedReceiptKey(key.kid, "Ed25519", key.toSpkiPem()))
    }
}

/** The demo account's receipts from memory, every other account's from its server. */
class DemoAwareReceiptRepository(private val real: ReceiptRepository, private val demo: ReceiptRepository) : ReceiptRepository {
    override suspend fun page(accountId: String, before: String?) = (if (isDemo(accountId)) demo else real).page(accountId, before)
    override suspend fun keys(accountId: String) = (if (isDemo(accountId)) demo else real).keys(accountId)
}

/** The demo server's pin in memory, with the demo (its key is new each time): nothing of the demo is written to the device. */
class DemoAwareReceiptKeyPinRepository(private val real: ReceiptKeyPinRepository, private val demo: DemoMode) : ReceiptKeyPinRepository {
    override suspend fun read(origin: String): Map<String, String>? = if (origin == DemoContent.ORIGIN) demo.store().receiptPins.value else real.read(origin)

    override suspend fun write(origin: String, pins: Map<String, String>) {
        if (origin == DemoContent.ORIGIN) demo.store().receiptPins.value = pins else real.write(origin, pins)
    }
}
