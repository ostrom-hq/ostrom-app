// SPDX-License-Identifier: Apache-2.0
// Ported from Ostrom's React Native app, src/ui/screens/history/HistoryScreen.tsx; laid out like the activity screen.
package run.ostrom.app.presentation.screens

import org.jetbrains.compose.resources.StringResource
import run.ostrom.app.domain.models.HistoryEntry
import run.ostrom.app.domain.models.ReceiptRejection
import run.ostrom.app.domain.models.RunOrigin
import run.ostrom.app.resources.Res
import run.ostrom.app.resources.history_origin_ci
import run.ostrom.app.resources.history_origin_isolated_runner
import run.ostrom.app.resources.history_origin_unknown
import run.ostrom.app.resources.history_origin_unverified
import run.ostrom.app.resources.history_origin_workstation_declared
import run.ostrom.app.resources.history_reason_bad_signature
import run.ostrom.app.resources.history_reason_digest_mismatch
import run.ostrom.app.resources.history_reason_malformed
import run.ostrom.app.resources.history_reason_missing_signature
import run.ostrom.app.resources.history_reason_unknown_algorithm
import run.ostrom.app.resources.history_reason_unknown_key

/**
 * Each row's key: the receipt's id, which stays when a newer receipt comes first; a repeated id (a server bug) gets its
 * rank among them, and a receipt this app cannot read its position.
 */
internal fun rowKeys(entries: List<HistoryEntry>): List<String> {
    val seen = HashMap<String, Int>()
    return entries.mapIndexed { index, entry ->
        val summary = entry.summary ?: return@mapIndexed "unreadable:$index"
        val id = summary.id
        val rank = seen.getOrElse(id) { 0 }
        seen[id] = rank + 1
        if (rank == 0) "receipt:$id" else "receipt:$id#$rank"
    }
}

/** The tag of a receipt's row: its id, or its position when this app cannot read it. */
internal fun rowTag(index: Int, entry: HistoryEntry): String {
    val summary = entry.summary ?: return "receipt-unreadable-$index"
    return "receipt-${summary.id}"
}

internal fun originText(origin: RunOrigin): StringResource = when (origin) {
    RunOrigin.WORKSTATION_DECLARED -> Res.string.history_origin_workstation_declared
    RunOrigin.ISOLATED_RUNNER -> Res.string.history_origin_isolated_runner
    RunOrigin.CI -> Res.string.history_origin_ci
    RunOrigin.UNKNOWN -> Res.string.history_origin_unknown
    RunOrigin.UNVERIFIED -> Res.string.history_origin_unverified
}

internal fun reasonText(reason: ReceiptRejection): StringResource = when (reason) {
    ReceiptRejection.MALFORMED -> Res.string.history_reason_malformed
    ReceiptRejection.MISSING_SIGNATURE -> Res.string.history_reason_missing_signature
    ReceiptRejection.UNKNOWN_ALGORITHM -> Res.string.history_reason_unknown_algorithm
    ReceiptRejection.UNKNOWN_KEY -> Res.string.history_reason_unknown_key
    ReceiptRejection.DIGEST_MISMATCH -> Res.string.history_reason_digest_mismatch
    ReceiptRejection.BAD_SIGNATURE -> Res.string.history_reason_bad_signature
}
