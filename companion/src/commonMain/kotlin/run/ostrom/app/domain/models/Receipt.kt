// SPDX-License-Identifier: Apache-2.0
// Ported from Ostrom's React Native app, src/api/contract/receipts.ts and src/ui/screens/history/useHistoryViewModel.ts.
package run.ostrom.app.domain.models

import kotlinx.serialization.json.JsonObject

/**
 * A page of signed receipts, newest first, not checked yet. [next] is the cursor of the older page, null on the last one.
 */
data class ReceiptPage(val items: List<HistoryEntry>, val next: String?)

/**
 * A public key the server says signs its receipts (`GET /api/v1/receipts/keys`). Never trusted for being served: only
 * through the pins of this device ([run.ostrom.app.domain.usecases.receipts.ReceiptKeyPinning]).
 *
 * @property rotation How the key it replaces handed it over, when it did.
 */
data class ServedReceiptKey(val kid: String, val alg: String, val publicKeyPem: String, val rotation: KeyHandover? = null)

/** The replaced key [kid]'s Ed25519 signature (base64) over `ostrom-receipt-key-rotation/v1\n` + the new key's raw hex. */
data class KeyHandover(val kid: String, val signature: String)

/** What this device found when it checked a receipt's signature itself, without trusting the server's word. */
sealed interface ReceiptCheck {
    /** Not checked yet: never shown as verified before it is. */
    data object Checking : ReceiptCheck

    data class Verified(val kid: String) : ReceiptCheck

    data class Rejected(val reason: ReceiptRejection) : ReceiptCheck

    /** No trusted key of the server could be had: nothing can be checked. */
    data object NoKey : ReceiptCheck
}

/** Why a receipt does not verify. */
enum class ReceiptRejection { MALFORMED, MISSING_SIGNATURE, UNKNOWN_ALGORITHM, UNKNOWN_KEY, DIGEST_MISMATCH, BAD_SIGNATURE }

/**
 * Where a check run of a workflow receipt comes from, as the history shows it. [UNVERIFIED]: the receipt claims Ostrom's
 * runner or the CI, but its signature was not verified on this device, so the claim proves nothing.
 */
enum class RunOrigin {
    WORKSTATION_DECLARED, ISOLATED_RUNNER, CI, UNKNOWN, UNVERIFIED;

    /** Run by Ostrom or the CI, on a verified receipt: the two origins shown as proven. */
    val proven: Boolean get() = this == ISOLATED_RUNNER || this == CI
}

/** A check node of a workflow receipt: its label and the server's words for what happened, shown as they are. */
data class CheckRun(val label: String, val outcome: String, val origin: RunOrigin)

/** A check node as the receipt reports it, before this device's check of the signature says what it proves. */
data class ReceiptCheckNode(val label: String, val outcome: String, val attestation: RunOrigin?)

/**
 * What the history shows of a receipt this app can read.
 *
 * @property result `approved`, `rejected`, or another action, from the last decision; null when nobody decided.
 * @property selfApproved The last decision was the asker's own yes, nobody else being able to give it.
 * @property by Who decided last, or who started the work when nobody decided.
 * @property at The time of the last decision, else of the receipt, in epoch milliseconds; null when unreadable.
 */
data class ReceiptSummary(
    val id: String,
    val result: String?,
    val selfApproved: Boolean,
    val by: String,
    val subjectKind: String,
    val subjectId: String,
    val at: Long?,
    val checkNodes: List<ReceiptCheckNode> = emptyList(),
)

/**
 * One receipt of the history: [raw] is the JSON object the server wrote (its signature covers every field, known to
 * this app or not), [summary] what it says when this app can read it (null for a newer shape, still listed and still
 * checked), and [check] what this device found of its signature.
 */
data class HistoryEntry(val raw: JsonObject, val summary: ReceiptSummary?, val check: ReceiptCheck = ReceiptCheck.Checking) {
    /** The check runs of a workflow receipt, in its order; `isolated_runner` and `ci` count only once it is verified. */
    val checkRuns: List<CheckRun>
        get() = summary?.checkNodes.orEmpty().map { node ->
            // A check signed before the field existed has none: it was the workstation's word.
            val origin = node.attestation ?: RunOrigin.WORKSTATION_DECLARED
            CheckRun(node.label, node.outcome, if (origin.proven && check !is ReceiptCheck.Verified) RunOrigin.UNVERIFIED else origin)
        }
}
