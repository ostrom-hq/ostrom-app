// SPDX-License-Identifier: Apache-2.0
// Ported from Ostrom's React Native app, src/repositories/ReceiptsRepository.ts and PinnedReceiptsRepository.ts.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.ReceiptPage
import run.ostrom.app.domain.models.ServedReceiptKey

/** The organization's signed receipts (`receipts.v1`). Failures are [run.ostrom.app.domain.models.AppException]s. */
interface ReceiptRepository {
    /** A page of receipts, newest first, the first one when [before] is null. */
    suspend fun page(accountId: String, before: String? = null): ReceiptPage

    /** The keys the server says sign its receipts; trusted only through [ReceiptKeyPinRepository]. */
    suspend fun keys(accountId: String): List<ServedReceiptKey>
}

/**
 * The receipt keys this device kept for each server origin, in its secure store: the raw key (64 hex characters) by
 * key id.
 */
interface ReceiptKeyPinRepository {
    /** The pins of [origin], empty before its first view; null when what is stored cannot be read (fail closed). */
    suspend fun read(origin: String): Map<String, String>?

    suspend fun write(origin: String, pins: Map<String, String>)
}
