// SPDX-License-Identifier: Apache-2.0

/** The `signature` field of a receipt: `<alg>;kid=<kid>;sha256=<digest>;sig=<base64>`. */
export interface SignatureField {
    readonly algorithm: string
    readonly kid: string
    readonly sha256: string
    readonly signature: string
}

/** Splits a signature field into its parts, or undefined when one is missing. */
export function parseSignatureField(field: string): SignatureField | undefined {
    const [algorithm, ...rest] = field.split(";")
    const parts = new Map<string, string>()
    for (const part of rest) {
        const equals = part.indexOf("=")
        if (equals > 0) parts.set(part.slice(0, equals), part.slice(equals + 1))
    }
    const kid = parts.get("kid")
    const sha256 = parts.get("sha256")
    const signature = parts.get("sig")
    if (algorithm === undefined || kid === undefined || sha256 === undefined || signature === undefined) {
        return undefined
    }
    return {algorithm, kid, sha256, signature}
}
