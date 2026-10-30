// SPDX-License-Identifier: AGPL-3.0-only
import {sha256} from "@noble/hashes/sha2.js"
import {utf8ToBytes} from "@noble/hashes/utils.js"

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/** Base64url without padding (RFC 4648 §5), the encoding of PKCE and OAuth `state`. */
export function base64Url(bytes: Uint8Array): string {
    let out = ""
    let buffer = 0
    let bits = 0
    for (const byte of bytes) {
        buffer = (buffer << 8) | byte
        bits += 8
        while (bits >= 6) {
            bits -= 6
            out += ALPHABET.charAt((buffer >> bits) & 0x3f)
        }
        buffer &= (1 << bits) - 1
    }
    if (bits > 0) out += ALPHABET.charAt((buffer << (6 - bits)) & 0x3f)
    return out
}

/** The S256 challenge of a PKCE verifier (RFC 7636 §4.2). */
export function pkceChallenge(verifier: string): string {
    return base64Url(sha256(utf8ToBytes(verifier)))
}

export interface Pkce {
    readonly verifier: string
    readonly challenge: string
}

/** A fresh PKCE pair from 32 random bytes (a 43-character verifier). */
export function createPkce(randomBytes: (length: number) => Uint8Array): Pkce {
    const verifier = base64Url(randomBytes(32))
    return {verifier, challenge: pkceChallenge(verifier)}
}
