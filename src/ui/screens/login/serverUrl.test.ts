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
    ])("reads %s as %s in a development build", (input, origin) => {
        expect(normalizeServerUrl(input, true)).toBe(origin)
    })

    it.each(["http://ostrom.acme.dev", "ftp://ostrom.acme.dev", "https://", "not a url at all", ""])(
        "refuses %s",
        (input) => {
            expect(normalizeServerUrl(input, true)).toBeUndefined()
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

    describe("by default, the build's own __DEV__", () => {
        // React Native declares `__DEV__` as a global constant; Jest sets it on the global object.
        const setDevelopment = (value: boolean): void => {
            Reflect.set(globalThis, "__DEV__", value)
        }

        beforeEach(() => {
            setDevelopment(false)
        })

        afterEach(() => {
            setDevelopment(true)
        })

        it("refuses HTTP on this computer in a release build", () => {
            expect(normalizeServerUrl("http://10.0.2.2:4961")).toBeUndefined()
            expect(normalizeServerUrl("localhost:4961")).toBe("https://localhost:4961")
        })

        it("takes it in a development build", () => {
            setDevelopment(true)
            expect(normalizeServerUrl("http://10.0.2.2:4961")).toBe("http://10.0.2.2:4961")
        })
    })
})
