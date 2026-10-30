import {receiptKey, receipts} from "@/api/mocks/fixtures"
import {ReceiptListSchema, ReceiptPublicKeySchema} from "@/api/contract/receipts"
import {checkReceipt, readInstanceKey} from "./receiptCheck"

const items = ReceiptListSchema.parse(receipts)
const served = ReceiptPublicKeySchema.parse(receiptKey)

describe("readInstanceKey", () => {
    it("reads the instance key the server announces", () => {
        expect(readInstanceKey(served)?.kid).toBe(receiptKey.kid)
    })

    it("refuses a key whose id is not the one announced", () => {
        expect(readInstanceKey({...served, kid: "0000000000000000"})).toBeUndefined()
    })

    it("refuses a PEM that is not an Ed25519 key", () => {
        expect(readInstanceKey({...served, publicKeyPem: "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----"})).toBeUndefined()
    })
})

describe("checkReceipt", () => {
    const key = readInstanceKey(served)

    it("verifies the golden receipts on the phone", () => {
        for (const item of items) {
            expect(checkReceipt(item, key)).toEqual({kind: "verified", kid: receiptKey.kid})
        }
    })

    it("rejects a receipt whose content was changed after signing", () => {
        const [first] = items
        if (first === undefined) throw new Error("no receipt in the vectors")
        const raw: unknown = first.raw
        if (typeof raw !== "object" || raw === null) throw new Error("receipt is not an object")
        const tampered = {...first, raw: {...raw, initiator: "mallory"}}
        expect(checkReceipt(tampered, key)).toEqual({kind: "rejected", reason: "digest-mismatch"})
    })

    it("checks nothing without the key", () => {
        const [first] = items
        if (first === undefined) throw new Error("no receipt in the vectors")
        expect(checkReceipt(first, undefined)).toEqual({kind: "no-key"})
    })
})
