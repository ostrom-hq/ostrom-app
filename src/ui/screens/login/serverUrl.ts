// SPDX-License-Identifier: AGPL-3.0-only
const LOCAL_HOSTS: ReadonlySet<string> = new Set(["localhost", "127.0.0.1", "10.0.2.2"])

/**
 * The origin of an Ostrom server typed by a person, or undefined when it is not one.
 * HTTPS only, except a server on this computer in a development build (localhost, the Android emulator's 10.0.2.2).
 * A store build never takes HTTP: on a real network 10.0.2.2 is any machine of the Wi-Fi, and the sign-in then the
 * token would go to it in clear (AUDIT-SECU-APP-2 A2).
 *
 * @param development Whether this is a development build (`__DEV__`, false in a release build).
 */
export function normalizeServerUrl(input: string, development: boolean = __DEV__): string | undefined {
    const text = input.trim()
    // Without a scheme: http for a server on this computer while developing, https for everything else.
    const onThisComputer = development && /^(localhost|127\.0\.0\.1|10\.0\.2\.2)(:\d+)?(\/|$)/i.test(text)
    const withScheme = /^[a-z]+:\/\//i.test(text) ? text : `${onThisComputer ? "http" : "https"}://${text}`
    let url: URL
    try {
        url = new URL(withScheme)
    } catch {
        return undefined
    }
    const secure = url.protocol === "https:"
    const local = development && url.protocol === "http:" && LOCAL_HOSTS.has(url.hostname)
    if (!secure && !local) return undefined
    return url.origin
}

