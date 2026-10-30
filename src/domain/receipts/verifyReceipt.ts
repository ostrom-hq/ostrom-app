// SPDX-License-Identifier: Apache-2.0
import * as ed from "@noble/ed25519"
import {sha512} from "@noble/hashes/sha2.js"
import {utf8ToBytes} from "@noble/hashes/utils.js"
import {base64ToBytes, sha256Hex} from "./bytes"
import {canonicalJson} from "./canonicalJson"
import type {ReceiptKey} from "./publicKey"
import {parseSignatureField} from "./signatureField"

ed.hashes.sha512 = sha512

export type ReceiptRejection =
    | "not-an-object"
    | "missing-signature"
    | "malformed-signature"
    | "unknown-algorithm"
    | "unknown-key"
    | "digest-mismatch"
    | "bad-signature"

export type ReceiptVerification =
    | {readonly ok: true; readonly digest: string; readonly kid: string}
    | {readonly ok: false; readonly reason: ReceiptRejection; readonly digest?: string}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value)
}

/** The 64 signature bytes, or undefined when the text is not base64 of exactly 64 bytes. */
function decodeSignature(text: string): Uint8Array | undefined {
    try {
        const bytes = base64ToBytes(text)
        return bytes.length === 64 ? bytes : undefined
    } catch {
        return undefined
    }
}

/**
 * Verifies a receipt offline, on the phone, against the instance key.
 * Takes the receipt as parsed from the wire, before any schema: a schema that drops an unknown field
 * would change the signed bytes.
 */
export function verifyReceipt(receipt: unknown, key: ReceiptKey): ReceiptVerification {
    if (!isRecord(receipt)) return {ok: false, reason: "not-an-object"}
    const {signature, ...body} = receipt
    if (typeof signature !== "string") return {ok: false, reason: "missing-signature"}
    const field = parseSignatureField(signature)
    if (field === undefined) return {ok: false, reason: "malformed-signature"}
    if (field.algorithm !== "ed25519") return {ok: false, reason: "unknown-algorithm"}
    if (field.kid !== key.kid) return {ok: false, reason: "unknown-key"}
    const canonical = canonicalJson(body)
    const digest = sha256Hex(canonical)
    if (field.sha256 !== digest) return {ok: false, reason: "digest-mismatch", digest}
    const signatureBytes = decodeSignature(field.signature)
    if (signatureBytes === undefined) return {ok: false, reason: "malformed-signature", digest}
    const valid = ed.verify(signatureBytes, utf8ToBytes(canonical), key.raw)
    return valid ? {ok: true, digest, kid: key.kid} : {ok: false, reason: "bad-signature", digest}
}
