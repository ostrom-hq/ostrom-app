// SPDX-License-Identifier: Apache-2.0
// Ported from Ostrom's React Native app, src/ui/screens/history/receiptCheck.ts and src/repositories/PinnedReceiptsRepository.ts.
package run.ostrom.app.domain.usecases.receipts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.HistoryEntry
import run.ostrom.app.domain.models.ReceiptCheck
import run.ostrom.app.domain.models.ReceiptPage
import run.ostrom.app.domain.models.ReceiptRejection
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.ReceiptKeyPinRepository
import run.ostrom.app.domain.repositories.ReceiptRepository
import run.ostrom.app.domain.repositories.find
import run.ostrom.app.domain.repositories.has
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.core.receipts.ReceiptPublicKey
import run.ostrom.core.receipts.ReceiptVerification
import run.ostrom.core.receipts.ReceiptVerificationFailure
import run.ostrom.core.receipts.ReceiptVerifierImpl

/** A page of receipts, the first one when [before] is null; [AppException.Unsupported] from a server without receipts. */
class LoadReceiptsUseCase(private val receipts: ReceiptRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, before: String? = null): ReceiptPage = guard(accountId) {
        if (!features.has(accountId, Features.RECEIPTS)) throw AppException.Unsupported(Features.RECEIPTS)
        receipts.page(accountId, before)
    }
}

/**
 * The receipt keys of the account's server this device trusts ([ReceiptKeyPinning]), its pins kept by server origin.
 * Pins that cannot be read trust nothing and are left as they are; pins that cannot be written still serve this view,
 * and the next one is a first view again.
 */
class TrustedReceiptKeysUseCase(
    private val receipts: ReceiptRepository,
    private val pins: ReceiptKeyPinRepository,
    private val accounts: AccountRepository,
    private val guard: AccountGuard,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend operator fun invoke(accountId: String): List<ReceiptPublicKey> = guard(accountId) {
        val origin = accounts.find(accountId)?.origin ?: throw AppException.Unauthorized()
        val served = receipts.keys(accountId)
        val kept = try {
            pins.read(origin)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        // Reading PEM keys and checking rotations is CPU work: off the main thread.
        val trust = withContext(dispatcher) { ReceiptKeyPinning.trust(served, kept) }
        if (trust.changed) {
            try {
                pins.write(origin, trust.pins)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Not kept: this view still uses its keys.
            }
        }
        trust.keys
    }
}

/** Checks each receipt's signature on this device against the trusted [keys], off the main thread (hashing and Ed25519). */
class CheckReceiptsUseCase(private val dispatcher: CoroutineDispatcher = Dispatchers.Default) {
    suspend operator fun invoke(entries: List<HistoryEntry>, keys: List<ReceiptPublicKey>): List<HistoryEntry> = withContext(dispatcher) {
        val verifier = ReceiptVerifierImpl(keys)
        entries.map { it.copy(check = checkReceipt(it.raw, keys.isNotEmpty(), verifier)) }
    }
}

/** What this device finds of one receipt's signature; [NoKey][ReceiptCheck.NoKey] when no key is trusted. */
internal fun checkReceipt(raw: JsonObject, anyKey: Boolean, verifier: ReceiptVerifierImpl): ReceiptCheck {
    if (!anyKey) return ReceiptCheck.NoKey
    val result = try {
        verifier.verify(raw)
    } catch (_: Exception) {
        // A platform without Ed25519, or a body the verifier cannot read: never a verified receipt.
        return ReceiptCheck.Rejected(ReceiptRejection.MALFORMED)
    }
    return when (result) {
        is ReceiptVerification.Verified -> ReceiptCheck.Verified(result.kid)
        is ReceiptVerification.Failed -> ReceiptCheck.Rejected(
            when (result.reason) {
                ReceiptVerificationFailure.MALFORMED -> ReceiptRejection.MALFORMED
                ReceiptVerificationFailure.MISSING_SIGNATURE -> ReceiptRejection.MISSING_SIGNATURE
                ReceiptVerificationFailure.UNKNOWN_ALGORITHM -> ReceiptRejection.UNKNOWN_ALGORITHM
                ReceiptVerificationFailure.UNKNOWN_KEY_ID -> ReceiptRejection.UNKNOWN_KEY
                ReceiptVerificationFailure.DIGEST_MISMATCH -> ReceiptRejection.DIGEST_MISMATCH
                ReceiptVerificationFailure.BAD_SIGNATURE -> ReceiptRejection.BAD_SIGNATURE
            },
        )
    }
}
