// SPDX-License-Identifier: AGPL-3.0-only
import {ReceiptKeysSchema} from "./receipts"

const key = {kid: "0123456789abcdef", alg: "Ed25519", publicKeyPem: "pem"}

describe("ReceiptKeysSchema", () => {
    it("keeps the signed rotation of a key, and drops an unreadable one without dropping the key", () => {
        const rotation = {kid: "fedcba9876543210", signature: "c2ln"}
        expect(ReceiptKeysSchema.parse({keys: [{...key, rotation}]}).keys).toEqual([{...key, rotation}])
        expect(ReceiptKeysSchema.parse({keys: [{...key, rotation: {kid: 7}}]}).keys).toEqual([key])
        expect(ReceiptKeysSchema.parse({keys: [key]}).keys).toEqual([key])
    })
})
