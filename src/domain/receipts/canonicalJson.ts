// SPDX-License-Identifier: Apache-2.0
/**
 * The canonical form of the Ostrom receipt format v1: JSON with keys sorted by UTF-16 code units at every level,
 * no whitespace, undefined fields omitted, arrays in their order (undefined items written as null).
 * Byte-for-byte the same as the prototype's `canonical()` (server/src/receipts.ts); checked against its vectors.
 */
export function canonicalJson(value: unknown): string {
    if (value === null || typeof value !== "object") return JSON.stringify(value)
    if (Array.isArray(value)) {
        const items: readonly unknown[] = value
        return `[${items.map((item) => (item === undefined ? "null" : canonicalJson(item))).join(",")}]`
    }
    const entries = Object.entries(value).filter(([, field]) => field !== undefined)
    // Default sort compares UTF-16 code units, like the prototype: never localeCompare.
    const keys = entries.map(([key]) => key).sort()
    const byKey = new Map(entries)
    return `{${keys.map((key) => `${JSON.stringify(key)}:${canonicalJson(byKey.get(key))}`).join(",")}}`
}
