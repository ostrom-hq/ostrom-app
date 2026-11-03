// SPDX-License-Identifier: AGPL-3.0-only
const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** Bytes to standard padded base64 (RFC 4648 §4), for tests that build keys and signatures. */
export function bytesToBase64(bytes: Uint8Array): string {
    let out = ""
    for (let index = 0; index < bytes.length; index += 3) {
        const [a = 0, b = 0, c = 0] = bytes.subarray(index, index + 3)
        const chunk = (a << 16) | (b << 8) | c
        const left = bytes.length - index
        out += ALPHABET.charAt((chunk >> 18) & 63) + ALPHABET.charAt((chunk >> 12) & 63)
        out += left > 1 ? ALPHABET.charAt((chunk >> 6) & 63) : "="
        out += left > 2 ? ALPHABET.charAt(chunk & 63) : "="
    }
    return out
}
