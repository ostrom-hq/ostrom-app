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
})
