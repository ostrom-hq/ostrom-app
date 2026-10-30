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

    it("reads the address from a URL or a Request too", async () => {
        expect((await fetch(new URL(`${MOCK_BASE_URL}/api/v1/auth/me`))).status).toBe(200)
        expect((await fetch(new Request(`${MOCK_BASE_URL}/api/v1/auth/me`))).status).toBe(200)
    })

    it("passes the method, the query and the JSON body", async () => {
        const refused = await fetch(`${MOCK_BASE_URL}/api/v1/auth/mobile/token`, {method: "POST", body: JSON.stringify({code: "bad"})})
        expect(refused.status).toBe(400)
        const older = await fetch(`${MOCK_BASE_URL}/api/v1/activity?before=x`)
        expect(older.status).toBe(200)
    })

    it("answers 204 without a body", async () => {
        const response = await fetch(`${MOCK_BASE_URL}/api/v1/auth/logout`, {method: "POST"})
        expect(response.status).toBe(204)
        expect(await response.text()).toBe("")
    })
})
