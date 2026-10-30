// SPDX-License-Identifier: Apache-2.0
import {sha256} from "@noble/hashes/sha2.js"
import {bytesToHex, utf8ToBytes} from "@noble/hashes/utils.js"

export {bytesToHex}

/** SHA-256 of the UTF-8 bytes of a text, in lowercase hexadecimal. */
export function sha256Hex(text: string): string {
    return bytesToHex(sha256(utf8ToBytes(text)))
}

/** SHA-256 of raw bytes, in lowercase hexadecimal. */
export function sha256HexOfBytes(bytes: Uint8Array): string {
    return bytesToHex(sha256(bytes))
}

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/**
 * Standard base64 (RFC 4648 §4) to bytes, padding optional, whitespace ignored.
 * Written here rather than relying on `atob`, whose presence depends on the JS engine.
 */
export function base64ToBytes(text: string): Uint8Array {
    const clean = text.replace(/\s+/g, "").replace(/=+$/, "")
    if (clean.length % 4 === 1 || !/^[A-Za-z0-9+/]*$/.test(clean)) throw new Error("not base64")
    const out = new Uint8Array(Math.floor((clean.length * 3) / 4))
    let buffer = 0
    let bits = 0
    let index = 0
    for (const char of clean) {
        buffer = (buffer << 6) | ALPHABET.indexOf(char)
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out[index] = (buffer >> bits) & 0xff
            index += 1
        }
    }
    return out
}
