// SPDX-License-Identifier: Apache-2.0
import * as ed from "@noble/ed25519"
import {sha512} from "@noble/hashes/sha2.js"
import {utf8ToBytes} from "@noble/hashes/utils.js"
import {base64ToBytes, bytesToHex} from "./bytes"
import type {ReceiptKey} from "./publicKey"

ed.hashes.sha512 = sha512

/** What the key being replaced signs to hand over to a new receipt key: a context line and the new key's raw bytes in hex. */
export function keyRotationMessage(key: ReceiptKey): Uint8Array {
    return utf8ToBytes(`ostrom-receipt-key-rotation/v1\n${bytesToHex(key.raw)}`)
}

/**
 * Whether `signature` (base64 Ed25519) is the rotation of `key` signed by the raw public key `by`. Anything unreadable
 * is refused, never thrown.
 */
export function verifyKeyRotation(key: ReceiptKey, signature: string, by: Uint8Array): boolean {
    let bytes: Uint8Array
    try {
        bytes = base64ToBytes(signature)
    } catch {
        return false
    }
    if (bytes.length !== 64) return false
    try {
        return ed.verify(bytes, keyRotationMessage(key), by)
    } catch {
        return false
    }
}
