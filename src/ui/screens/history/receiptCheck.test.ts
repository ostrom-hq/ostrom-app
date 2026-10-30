// SPDX-License-Identifier: AGPL-3.0-only
import {receiptKey, receipts} from "@/api/mocks/fixtures"
import {ReceiptKeysSchema, ReceiptPageSchema, type ReceiptListItem} from "@/api/contract/receipts"
import {checkReceipt, readInstanceKeys} from "./receiptCheck"

const {items} = ReceiptPageSchema.parse({items: receipts})
const {keys: served} = ReceiptKeysSchema.parse({keys: [receiptKey]})
const otherPem = "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----"

function firstItem(): ReceiptListItem {
    const [first] = items
    if (first === undefined) throw new Error("no receipt in the vectors")
    return first
}

function withRaw(raw: unknown): ReceiptListItem {
    return {readable: false, raw, detail: "test"}
}

describe("readInstanceKeys", () => {
    it("reads the instance key the server announces", () => {
        expect([...readInstanceKeys(served).keys()]).toEqual([receiptKey.kid])
    })

    it("leaves out a key whose id is not the one announced, a non-Ed25519 PEM and another algorithm", () => {
        const [key] = served
        if (key === undefined) throw new Error("no key")
        const keys = readInstanceKeys([
            {...key, kid: "0000000000000000"},
            {...key, kid: "1111111111111111", publicKeyPem: otherPem},
            {...key, alg: "rsa"},
        ])
        expect(keys.size).toBe(0)
    })
})

describe("checkReceipt", () => {
    const keys = readInstanceKeys(served)

    it("verifies the golden receipts on the phone", () => {
        for (const item of items) {
            expect(checkReceipt(item, keys)).toEqual({kind: "verified", kid: receiptKey.kid})
        }
    })

    it("rejects a receipt whose content was changed after signing", () => {
        const raw: unknown = firstItem().raw
        if (typeof raw !== "object" || raw === null) throw new Error("receipt is not an object")
        expect(checkReceipt(withRaw({...raw, initiator: "mallory"}), keys)).toEqual({kind: "rejected", reason: "digest-mismatch"})
    })

    it("rejects a receipt signed by a key the instance does not serve", () => {
        const raw: unknown = firstItem().raw
        if (typeof raw !== "object" || raw === null || !("signature" in raw) || typeof raw.signature !== "string") {
            throw new Error("receipt has no signature")
        }
        const signature = raw.signature.replace(receiptKey.kid, "ffffffffffffffff")
        expect(checkReceipt(withRaw({...raw, signature}), keys)).toEqual({kind: "rejected", reason: "unknown-key"})
    })

    it("says why a receipt without a readable signature fails", () => {
        expect(checkReceipt(withRaw("not a receipt"), keys)).toEqual({kind: "rejected", reason: "not-an-object"})
        expect(checkReceipt(withRaw({id: "r"}), keys)).toEqual({kind: "rejected", reason: "missing-signature"})
        expect(checkReceipt(withRaw({signature: 42}), keys)).toEqual({kind: "rejected", reason: "missing-signature"})
        expect(checkReceipt(withRaw({signature: "ed25519"}), keys)).toEqual({kind: "rejected", reason: "malformed-signature"})
        expect(checkReceipt(withRaw(null), keys)).toEqual({kind: "rejected", reason: "not-an-object"})
    })

    it("checks nothing without a key", () => {
        expect(checkReceipt(firstItem(), new Map())).toEqual({kind: "no-key"})
    })
})
