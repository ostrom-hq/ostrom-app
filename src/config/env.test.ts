import {DEFAULT_SERVER_URL, serverUrlFrom} from "./env"

describe("serverUrlFrom", () => {
    it("proposes the server the build was made for", () => {
        expect(serverUrlFrom("http://10.0.2.2:4961")).toBe("http://10.0.2.2:4961")
    })

    it("falls back on the hosted Ostrom", () => {
        expect(serverUrlFrom(undefined)).toBe("https://app.ostrom.run")
        expect(serverUrlFrom("")).toBe("https://app.ostrom.run")
        expect(DEFAULT_SERVER_URL).toBe("https://app.ostrom.run")
    })
})
