// SPDX-License-Identifier: AGPL-3.0-only
import type {ReceiptListItem, ReceiptPublicKey} from "@/api/contract/receipts"
import {parseEd25519PublicKeyPem, type ReceiptKey} from "@/domain/receipts/publicKey"
import {parseSignatureField} from "@/domain/receipts/signatureField"
import {type ReceiptRejection, verifyReceipt} from "@/domain/receipts/verifyReceipt"

/** What this phone found when it checked a receipt's signature itself, without trusting the server's word. */
export type ReceiptCheck =
    | {readonly kind: "verified"; readonly kid: string}
    | {readonly kind: "rejected"; readonly reason: ReceiptRejection}
    /** No usable instance key could be fetched: nothing can be checked. */
    | {readonly kind: "no-key"}

/**
 * The instance keys, by id, keeping only an Ed25519 PEM whose id is the one the server announces.
 * A server that names one key and serves another gets no verification with it, never a false "verified".
 */
export function readInstanceKeys(served: readonly ReceiptPublicKey[]): ReadonlyMap<string, ReceiptKey> {
    const keys = new Map<string, ReceiptKey>()
    for (const candidate of served) {
        if (candidate.alg.toLowerCase() !== "ed25519") continue
        try {
            const key = parseEd25519PublicKeyPem(candidate.publicKeyPem)
            if (key.kid === candidate.kid) keys.set(key.kid, key)
        } catch {
            // Not an Ed25519 key: left out.
        }
    }
    return keys
}

/** The id of the key a receipt says it was signed with, when its signature field can be read. */
function signingKid(raw: unknown): string | undefined {
    if (typeof raw !== "object" || raw === null || !("signature" in raw) || typeof raw.signature !== "string") return undefined
    return parseSignatureField(raw.signature)?.kid
}

export function checkReceipt(item: ReceiptListItem, keys: ReadonlyMap<string, ReceiptKey>): ReceiptCheck {
    const kid = signingKid(item.raw)
    // The named key, else any key: the verifier then says why (unknown key, unreadable signature…).
    const key = (kid === undefined ? undefined : keys.get(kid)) ?? keys.values().next().value
    if (key === undefined) return {kind: "no-key"}
    const result = verifyReceipt(item.raw, key)
    return result.ok ? {kind: "verified", kid: result.kid} : {kind: "rejected", reason: result.reason}
}
