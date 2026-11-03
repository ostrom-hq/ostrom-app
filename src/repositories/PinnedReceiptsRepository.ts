// SPDX-License-Identifier: AGPL-3.0-only
import {hexToBytes} from "@noble/hashes/utils.js"
import {z} from "zod"
import type {ReceiptEnvelope, ReceiptPage, ReceiptPublicKey} from "@/api/contract/receipts"
import {bytesToHex} from "@/domain/receipts/bytes"
import {verifyKeyRotation} from "@/domain/receipts/keyRotation"
import {parseEd25519PublicKeyPem, type ReceiptKey} from "@/domain/receipts/publicKey"
import type {SecureStorage} from "@/services/storage/SecureStorage"
import type {ReceiptsRepository} from "./ReceiptsRepository"

/** The receipt keys this phone keeps, by server address, then by key id: the raw key in hex. */
export const RECEIPT_KEY_PINS = "ostrom.receiptKeyPins"

const PinsSchema = z.record(z.string(), z.record(z.string(), z.string()))
type Pins = z.output<typeof PinsSchema>

/** A served Ed25519 key whose id is the one announced; undefined for any other. */
function usableKey(served: ReceiptPublicKey): ReceiptKey | undefined {
    if (served.alg.toLowerCase() !== "ed25519") return undefined
    try {
        const key = parseEd25519PublicKeyPem(served.publicKeyPem)
        return key.kid === served.kid ? key : undefined
    } catch {
        return undefined
    }
}

function readPins(text: string | undefined): Pins {
    if (text === undefined) return {}
    try {
        const parsed = PinsSchema.safeParse(JSON.parse(text))
        return parsed.success ? parsed.data : {}
    } catch {
        return {}
    }
}

/**
 * The receipts of a server, verified only with the keys this phone kept from its first view of that server
 * (AUDIT-SECU-APP-2 A5, S2-18). A key that appears later is used only when a kept key signed its rotation; otherwise
 * the receipts it signs read as signed with an unknown key, never as verified.
 */
export class PinnedReceiptsRepository implements ReceiptsRepository {
    constructor(
        private readonly inner: ReceiptsRepository,
        private readonly storage: SecureStorage,
        private readonly serverUrl: string,
    ) {}

    list(): Promise<ReceiptPage> {
        return this.inner.list()
    }

    get(id: string): Promise<ReceiptEnvelope> {
        return this.inner.get(id)
    }

    async keys(): Promise<readonly ReceiptPublicKey[]> {
        const served = (await this.inner.keys()).flatMap((candidate) => {
            const key = usableKey(candidate)
            return key === undefined ? [] : [{candidate, key}]
        })
        const pins = readPins(await this.storage.get(RECEIPT_KEY_PINS))
        const kept = new Map(Object.entries(pins[this.serverUrl] ?? {}))
        const firstView = kept.size === 0
        const trusted = new Map(kept)
        if (firstView) for (const {key} of served) trusted.set(key.kid, bytesToHex(key.raw))
        // A rotation signed by a kept key, or by a key rotated from one (C from B from A).
        let grew = !firstView
        while (grew) {
            grew = false
            for (const {candidate, key} of served) {
                const {rotation} = candidate
                if (rotation === undefined || trusted.has(key.kid)) continue
                const by = trusted.get(rotation.kid)
                if (by === undefined || !verifyKeyRotation(key, rotation.signature, hexToBytes(by))) continue
                trusted.set(key.kid, bytesToHex(key.raw))
                grew = true
            }
        }
        if (trusted.size > kept.size) {
            // Not kept: this view still uses its keys, and the next one is a first view again.
            await this.storage
                .set(RECEIPT_KEY_PINS, JSON.stringify({...pins, [this.serverUrl]: Object.fromEntries(trusted)}))
                .catch(() => undefined)
        }
        // Compared by raw key, not only by its 16-hex id.
        return served.filter(({key}) => trusted.get(key.kid) === bytesToHex(key.raw)).map(({candidate}) => candidate)
    }
}
