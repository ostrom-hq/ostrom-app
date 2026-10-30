// SPDX-License-Identifier: AGPL-3.0-only
import {serverUrlFrom} from "./env"

describe("serverUrlFrom", () => {
    it("proposes the server the build was made for, as an origin", () => {
        expect(serverUrlFrom("http://10.0.2.2:4961")).toBe("http://10.0.2.2:4961")
        expect(serverUrlFrom(" https://ostrom.acme.dev/ ")).toBe("https://ostrom.acme.dev")
    })

    it("falls back on the hosted Ostrom when unset, blank or not a server address", () => {
        for (const configured of [undefined, "", "  ", "http://staging.example.com"]) {
            expect(serverUrlFrom(configured)).toBe("https://app.ostrom.run")
        }
    })
})
