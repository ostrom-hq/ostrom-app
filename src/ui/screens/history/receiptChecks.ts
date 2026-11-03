// SPDX-License-Identifier: AGPL-3.0-only
import type {ReceiptListItem} from "@/api/contract/receipts"
import {bytesToHex} from "@/domain/receipts/bytes"
import type {ReceiptKey} from "@/domain/receipts/publicKey"
import {checkReceipt, type ReceiptCheck} from "./receiptCheck"

/** What a row shows of its signature: neutral until this phone has checked it, never "verified" before. */
export type ShownCheck = ReceiptCheck | {readonly kind: "checking"}

export const CHECKING: ShownCheck = {kind: "checking"}

/** The results of this phone's checks with one set of instance keys, kept by receipt id. */
export interface ReceiptChecks {
    /** The result kept for this receipt, when the same content was checked with these keys. */
    readonly cached: (item: ReceiptListItem) => ReceiptCheck | undefined
    /** Checks the receipt, or gives the result kept for it. */
    readonly check: (item: ReceiptListItem) => ReceiptCheck
}

/** The instance keys as one string: a key added, removed or rotated gives another. */
export function keysFingerprint(keys: ReadonlyMap<string, ReceiptKey>): string {
    return [...keys.values()]
        .map((key) => `${key.kid}:${bytesToHex(key.raw)}`)
        .sort()
        .join(",")
}

/**
 * Checks receipts against these keys, each result kept by receipt id. A result is given back only for the content it
 * was found on: a receipt changed after signing, under the id of one already verified, is checked again. A receipt this
 * app cannot read has no id and is never kept.
 */
export function createReceiptChecks(keys: ReadonlyMap<string, ReceiptKey>): ReceiptChecks {
    const kept = new Map<string, {readonly content: string; readonly check: ReceiptCheck}>()
    const cached = (item: ReceiptListItem): ReceiptCheck | undefined => {
        if (!item.readable) return undefined
        const hit = kept.get(item.view.id)
        return hit !== undefined && hit.content === JSON.stringify(item.raw) ? hit.check : undefined
    }
    return {
        cached,
        check: (item) => {
            const hit = cached(item)
            if (hit !== undefined) return hit
            const check = checkReceipt(item, keys)
            if (item.readable) kept.set(item.view.id, {content: JSON.stringify(item.raw), check})
            return check
        },
    }
}
