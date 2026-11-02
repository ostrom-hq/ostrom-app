// SPDX-License-Identifier: AGPL-3.0-only
import {normalizeServerUrl} from "./serverUrl"

describe("normalizeServerUrl", () => {
    it.each([
        ["https://ostrom.acme.dev/", "https://ostrom.acme.dev"],
        ["  ostrom.acme.dev/console ", "https://ostrom.acme.dev"],
        ["https://ostrom.acme.dev:8443", "https://ostrom.acme.dev:8443"],
        ["http://localhost:4961", "http://localhost:4961"],
        ["http://10.0.2.2:4961", "http://10.0.2.2:4961"],
        ["localhost:4961", "http://localhost:4961"],
    ])("reads %s as %s", (input, origin) => {
        expect(normalizeServerUrl(input)).toBe(origin)
    })

    it.each(["http://ostrom.acme.dev", "ftp://ostrom.acme.dev", "https://", "not a url at all", ""])(
        "refuses %s",
        (input) => {
            expect(normalizeServerUrl(input)).toBeUndefined()
        },
    )

    describe("in a store build", () => {
        it.each(["http://localhost:4961", "http://10.0.2.2:4961", "http://127.0.0.1:4961"])("refuses %s", (input) => {
            expect(normalizeServerUrl(input, false)).toBeUndefined()
        })

        it.each([
            ["https://ostrom.acme.dev/", "https://ostrom.acme.dev"],
            // Without a scheme, a local address is read as HTTPS like any other.
            ["10.0.2.2:4961", "https://10.0.2.2:4961"],
        ])("reads %s as %s", (input, origin) => {
            expect(normalizeServerUrl(input, false)).toBe(origin)
        })
    })

    it("takes HTTP on this computer in a development build, the Jest default", () => {
        expect(__DEV__).toBe(true)
        expect(normalizeServerUrl("http://10.0.2.2:4961", true)).toBe("http://10.0.2.2:4961")
    })
})
