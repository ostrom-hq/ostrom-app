// SPDX-License-Identifier: Apache-2.0
import {base64ToBytes, bytesToHex, sha256HexOfBytes} from "./bytes"

/** An instance's receipt key: the raw Ed25519 public key and its id (first 16 hex of SHA-256 of the SPKI DER). */
export interface ReceiptKey {
    readonly raw: Uint8Array
    readonly kid: string
}

// SEQUENCE { SEQUENCE { OID 1.3.101.112 (Ed25519) } BIT STRING (33 bytes, 0 unused bits) }
const ED25519_SPKI_PREFIX = "302a300506032b6570032100"

/** Reads an Ed25519 public key in SPKI PEM, as served by `GET /api/receipts/pubkey`. */
export function parseEd25519PublicKeyPem(pem: string): ReceiptKey {
    const match = /-----BEGIN PUBLIC KEY-----([\s\S]+?)-----END PUBLIC KEY-----/.exec(pem)
    const body = match?.[1]
    const der = body === undefined ? undefined : base64ToBytes(body)
    if (der?.length !== 44 || bytesToHex(der.subarray(0, 12)) !== ED25519_SPKI_PREFIX) {
        throw new Error("not an Ed25519 public key")
    }
    return {raw: der.slice(12), kid: sha256HexOfBytes(der).slice(0, 16)}
}
