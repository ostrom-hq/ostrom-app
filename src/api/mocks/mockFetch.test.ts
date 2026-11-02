// SPDX-License-Identifier: AGPL-3.0-only
import {MOCK_BASE_URL, me} from "./fixtures"
import {mockFetch} from "./mockFetch"
import {createMockServer} from "./mockServer"

describe("mockFetch", () => {
    const fetch = mockFetch(createMockServer())

    it("answers a GET from the in-memory server, without any network", async () => {
        const response = await fetch(`${MOCK_BASE_URL}/api/v1/auth/me`)
        expect(response.status).toBe(200)
        expect(await response.json()).toEqual(me)
    })

    it("reads a URL or a Request too, with the Request's method and body", async () => {
        expect((await fetch(new URL(`${MOCK_BASE_URL}/api/v1/auth/me`))).status).toBe(200)
        const request = new Request(`${MOCK_BASE_URL}/api/v1/auth/mobile/token`, {method: "POST", body: JSON.stringify({code: "good-code", codeVerifier: "v", redirectUri: "ostrom://auth/callback"})})
        expect(await (await fetch(request)).json()).toEqual({
            token: "ost_mock_token",
            user: {login: "rev-b"},
            unlockKey: "mock-unlock-key",
        })
    })

    it("fails as fetch does, with a rejected promise, on a body that is not JSON", async () => {
        await expect(fetch(`${MOCK_BASE_URL}/api/v1/auth/mobile/token`, {method: "POST", body: "not json"})).rejects.toThrow(SyntaxError)
    })

    it("passes the method, the query and the JSON body", async () => {
        const refused = await fetch(`${MOCK_BASE_URL}/api/v1/auth/mobile/token`, {method: "POST", body: JSON.stringify({code: "bad"})})
        expect(refused.status).toBe(400)
        const older = await fetch(`${MOCK_BASE_URL}/api/v1/activity?before=x`)
        expect(older.status).toBe(200)
    })

    it("reads the Ostrom-Org header", async () => {
        const response = await fetch(`${MOCK_BASE_URL}/api/v1/activity`, {headers: {"ostrom-org": "org_other"}})
        expect(response.status).toBe(404)
    })

    it("answers 204 without a body", async () => {
        const response = await fetch(`${MOCK_BASE_URL}/api/v1/auth/logout`, {method: "POST"})
        expect(response.status).toBe(204)
        expect(await response.text()).toBe("")
    })
})
