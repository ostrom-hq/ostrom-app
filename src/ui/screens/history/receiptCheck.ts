// SPDX-License-Identifier: AGPL-3.0-only
import type {ReceiptListItem, ReceiptPublicKey} from "@/api/contract/receipts"
import {parseEd25519PublicKeyPem, type ReceiptKey} from "@/domain/receipts/publicKey"
import {type ReceiptRejection, verifyReceipt} from "@/domain/receipts/verifyReceipt"

/** What this phone found when it checked a receipt's signature itself, without trusting the server's word. */
export type ReceiptCheck =
    | {readonly kind: "verified"; readonly kid: string}
    | {readonly kind: "rejected"; readonly reason: ReceiptRejection}
    /** The instance key could not be fetched, or it is not the key the server names: nothing can be checked. */
    | {readonly kind: "no-key"}

/**
 * The instance key, only if its PEM is an Ed25519 key whose id is the one the server announces.
 * A server that names one key and serves another gets no verification at all, never a false "verified".
 */
export function readInstanceKey(served: ReceiptPublicKey): ReceiptKey | undefined {
    try {
        const key = parseEd25519PublicKeyPem(served.publicKeyPem)
        return key.kid === served.kid ? key : undefined
    } catch {
        return undefined
    }
}

export function checkReceipt(item: ReceiptListItem, key: ReceiptKey | undefined): ReceiptCheck {
    if (key === undefined) return {kind: "no-key"}
    const result = verifyReceipt(item.raw, key)
    return result.ok ? {kind: "verified", kid: result.kid} : {kind: "rejected", reason: result.reason}
}
