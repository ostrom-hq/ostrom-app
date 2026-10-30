// SPDX-License-Identifier: AGPL-3.0-only
const LOCAL_HOSTS: ReadonlySet<string> = new Set(["localhost", "127.0.0.1", "10.0.2.2"])

/**
 * The origin of an Ostrom server typed by a person, or undefined when it is not one.
 * HTTPS only, except a server on this computer during development (localhost, the Android emulator's 10.0.2.2).
 */
export function normalizeServerUrl(input: string): string | undefined {
    const text = input.trim()
    // Without a scheme: http for a server on this computer, https for everything else.
    const onThisComputer = /^(localhost|127\.0\.0\.1|10\.0\.2\.2)(:\d+)?(\/|$)/i.test(text)
    const withScheme = /^[a-z]+:\/\//i.test(text) ? text : `${onThisComputer ? "http" : "https"}://${text}`
    let url: URL
    try {
        url = new URL(withScheme)
    } catch {
        return undefined
    }
    const secure = url.protocol === "https:"
    const local = url.protocol === "http:" && LOCAL_HOSTS.has(url.hostname)
    if (!secure && !local) return undefined
    return url.origin
}

