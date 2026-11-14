// SPDX-License-Identifier: Apache-2.0
// Ported from Ostrom's React Native app, src/repositories/ReceiptsRepositoryImpl.ts and src/api/contract/receipts.ts.
package run.ostrom.app.infrastructure.repositories

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.app.domain.models.HistoryEntry
import run.ostrom.app.domain.models.KeyHandover
import run.ostrom.app.domain.models.ReceiptCheckNode
import run.ostrom.app.domain.models.ReceiptPage
import run.ostrom.app.domain.models.ReceiptSummary
import run.ostrom.app.domain.models.RunOrigin
import run.ostrom.app.domain.models.ServedReceiptKey
import run.ostrom.app.domain.repositories.ReceiptRepository
import run.ostrom.core.api.models.CheckAttestation
import run.ostrom.core.api.models.Receipt
import run.ostrom.core.api.models.ReceiptSubjectKind
import run.ostrom.core.receipts.ReceiptJson

/**
 * The receipts over the typed client, as the server wrote them (`GET /api/v1/receipts`): decoding into the model would
 * drop the fields this version does not know, and a genuine receipt of a newer server would then read as modified.
 */
class RemoteReceiptRepository(private val calls: AccountCalls) : ReceiptRepository {
    override suspend fun page(accountId: String, before: String?): ReceiptPage = calls.withAccount(accountId) { client ->
        val page = client.receipts.listRaw(before = before)
        ReceiptPage(page.items.map(::readReceipt), page.next)
    }

    override suspend fun keys(accountId: String): List<ServedReceiptKey> = calls.withAccount(accountId) { client ->
        client.receipts.keys().map { key -> ServedReceiptKey(key.kid, key.alg, key.publicKeyPem, key.rotation?.let { KeyHandover(it.kid, it.signature) }) }
    }
}

/**
 * A receipt for the history: the untouched object, for the signature, and what it says, for the row. A receipt this
 * version cannot read (a newer shape) has no summary: it still has its row and is still checked.
 */
fun readReceipt(raw: JsonObject): HistoryEntry {
    val receipt = try {
        ReceiptJson.decodeFromJsonElement(Receipt.serializer(), raw)
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
    return HistoryEntry(raw, receipt?.summary(raw))
}

private fun Receipt.summary(raw: JsonObject): ReceiptSummary {
    val decision = decisions.lastOrNull()
    // A kind this version does not know is shown as the server wrote it.
    val kind = if (subject.kind == ReceiptSubjectKind.UNKNOWN) ((raw["subject"] as? JsonObject)?.get("kind") as? JsonPrimitive)?.content.orEmpty() else subject.kind.value
    return ReceiptSummary(
        id = id,
        result = decision?.result,
        selfApproved = decision?.selfApproved == true,
        by = decision?.by ?: initiator,
        subjectKind = kind,
        subjectId = subject.id,
        at = millisOf(decision?.at ?: createdAt),
        checkNodes = nodes.orEmpty().filter { it.kind == "check" }.map { node ->
            ReceiptCheckNode(
                node.label,
                node.outcome,
                when (node.attestation) {
                    null -> null
                    CheckAttestation.WORKSTATION_DECLARED -> RunOrigin.WORKSTATION_DECLARED
                    CheckAttestation.ISOLATED_RUNNER -> RunOrigin.ISOLATED_RUNNER
                    CheckAttestation.CI -> RunOrigin.CI
                    CheckAttestation.UNKNOWN -> RunOrigin.UNKNOWN
                },
            )
        },
    )
}
