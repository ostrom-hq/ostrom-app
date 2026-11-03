// SPDX-License-Identifier: Apache-2.0
import * as ed from "@noble/ed25519"
import {utf8ToBytes} from "@noble/hashes/utils.js"
import {bytesToBase64} from "@/test/base64"
import {bytesToHex} from "./bytes"
import {keyRotationMessage, verifyKeyRotation} from "./keyRotation"
import type {ReceiptKey} from "./publicKey"

const seed = (fill: number): Uint8Array => new Uint8Array(32).fill(fill)
const keyOf = (fill: number): ReceiptKey => ({raw: ed.getPublicKey(seed(fill)), kid: `kid-${String(fill)}`})
const base64 = bytesToBase64

describe("verifyKeyRotation", () => {
    it("accepts a new key signed by the key it replaces, over the new key's bytes", () => {
        const next = keyOf(2)
        const signature = base64(ed.sign(keyRotationMessage(next), seed(1)))
        expect(keyRotationMessage(next)).toEqual(utf8ToBytes(`ostrom-receipt-key-rotation/v1\n${bytesToHex(next.raw)}`))
        expect(verifyKeyRotation(next, signature, keyOf(1).raw)).toBe(true)
    })

    it("refuses a rotation signed by another key, over another key, or unreadable", () => {
        const next = keyOf(2)
        expect(verifyKeyRotation(next, base64(ed.sign(keyRotationMessage(next), seed(3))), keyOf(1).raw)).toBe(false)
        expect(verifyKeyRotation(next, base64(ed.sign(keyRotationMessage(keyOf(4)), seed(1))), keyOf(1).raw)).toBe(false)
        expect(verifyKeyRotation(next, "not base64!", keyOf(1).raw)).toBe(false)
        expect(verifyKeyRotation(next, base64(new Uint8Array(63)), keyOf(1).raw)).toBe(false)
        // Not a public key at all: refused, never thrown.
        expect(verifyKeyRotation(next, base64(new Uint8Array(64)), new Uint8Array(31))).toBe(false)
    })
})
