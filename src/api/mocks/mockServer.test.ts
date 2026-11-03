// SPDX-License-Identifier: AGPL-3.0-only
import {createMockServer} from "./mockServer"

describe("createMockServer", () => {
    it("answers 404 on an id that is not valid percent-encoding, instead of throwing", () => {
        const answer = createMockServer().handle({method: "GET", path: "/api/v1/approvals/%E0%A4%A/view", query: {}, body: undefined})
        expect(answer).toEqual({status: 404, body: {error: {type: "not_found", message: "No such request"}}})
    })

    it("decodes an id before looking it up", () => {
        const answer = createMockServer().handle({method: "GET", path: "/api/v1/approvals/appr%5F0001/view", query: {}, body: undefined})
        expect(answer.status).toBe(200)
    })
})

describe("createMockServer and the phone unlock", () => {
    it.each([undefined, null, {}, {unlockKey: "another-key"}])("refuses an unlock without the key it gave: %p", (body) => {
        expect(createMockServer().handle({method: "POST", path: "/api/v1/auth/mobile/unlock", query: {}, body}).status).toBe(403)
    })
})

describe("createMockServer and Ostrom-Org", () => {
    const get = (path: string, org?: string): {method: string; path: string; query: Record<string, string>; body: undefined; org?: string} =>
        org === undefined ? {method: "GET", path, query: {}, body: undefined} : {method: "GET", path, query: {}, body: undefined, org}

    it("acts in the one membership without the header, and in the organization the header names", () => {
        const server = createMockServer()
        expect(server.handle(get("/api/v1/approvals/queue")).status).toBe(200)
        expect(server.handle(get("/api/v1/approvals/queue", "org_acme")).status).toBe(200)
    })

    it("answers 404 for an organization the caller is not a member of, as for anything of another organization", () => {
        expect(createMockServer().handle(get("/api/v1/approvals/queue", "org_other"))).toEqual({
            status: 404,
            body: {error: {type: "not_found", message: "No such organization"}},
        })
    })

    it("wants the header from a member of several organizations: 409 org_required", () => {
        const server = createMockServer({memberships: ["org_acme", "org_beta"]})
        expect(server.handle(get("/api/v1/approvals/queue"))).toEqual({
            status: 409,
            body: {error: {type: "org_required", message: "Name the organization with the Ostrom-Org header"}},
        })
        expect(server.handle(get("/api/v1/approvals/queue", "org_beta")).status).toBe(200)
    })

    it("answers 404 to a person in no organization, never org_required", () => {
        expect(createMockServer({memberships: []}).handle(get("/api/v1/approvals/queue")).status).toBe(404)
    })

    it("asks nothing on a route outside the organization", () => {
        const server = createMockServer({memberships: ["org_acme", "org_beta"]})
        expect(server.handle(get("/api/v1/receipts/keys")).status).toBe(200)
        expect(server.handle(get("/api/v1/me/notifications")).status).toBe(200)
        expect(server.handle(get("/api/v1/auth/me")).status).toBe(200)
        expect(server.handle(get("/api/v1/auth/me/account")).status).toBe(200)
    })

    it("lists the caller's organizations as server#64 does, without the header", () => {
        const server = createMockServer({memberships: ["org_acme", "org_globex", "org_beta"]})
        expect(server.handle(get("/api/v1/me/organizations"))).toEqual({
            status: 200,
            body: {
                items: [
                    {id: "org_acme", slug: "acme", name: "acme", avatarUrl: "https://avatars.test/acme", role: "member"},
                    {id: "org_globex", slug: "globex", name: "globex", avatarUrl: "", role: "member"},
                    {id: "org_beta", slug: "org_beta", name: "org_beta", avatarUrl: "", role: "member"},
                ],
            },
        })
    })
})
