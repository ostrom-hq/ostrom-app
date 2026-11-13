// SPDX-License-Identifier: AGPL-3.0-only
import {z} from "zod"
import {pkceChallenge} from "@/services/signIn/pkce"
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

describe("createMockServer and the code exchange", () => {
    const exchange = (body: unknown): number =>
        createMockServer().handle({method: "POST", path: "/api/v1/auth/mobile/token", query: {}, body}).status

    it("takes a code of the E2E auth host only with the verifier of its challenge, for the app's return", () => {
        const verifier = "v".repeat(43)
        const code = `pkce:${pkceChallenge(verifier)}`
        expect(exchange({code, codeVerifier: verifier, redirectUri: "ostrom://auth/callback"})).toBe(200)
        expect(exchange({code, codeVerifier: "w".repeat(43), redirectUri: "ostrom://auth/callback"})).toBe(400)
        expect(exchange({code, codeVerifier: verifier, redirectUri: "ostrom://elsewhere"})).toBe(400)
    })
})

describe("createMockServer with many rows", () => {
    const get = (server: ReturnType<typeof createMockServer>, path: string): unknown =>
        server.handle({method: "GET", path, query: {}, body: undefined}).body

    it("adds as many waiting requests, activity rows and receipts as asked, for the performance pass", () => {
        const server = createMockServer({many: 12})
        const queue = z.object({pending: z.array(z.unknown())}).parse(get(server, "/api/v1/approvals/queue"))
        const fewQueue = z.object({pending: z.array(z.unknown())}).parse(get(createMockServer(), "/api/v1/approvals/queue"))
        expect(queue.pending.length - fewQueue.pending.length).toBe(12)
        const listOf = (path: string, mock = server): number => z.object({items: z.array(z.unknown())}).parse(get(mock, path)).items.length
        expect(listOf("/api/v1/activity") - listOf("/api/v1/activity", createMockServer())).toBe(12)
        expect(listOf("/api/v1/receipts") - listOf("/api/v1/receipts", createMockServer())).toBe(12)
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
        expect(server.handle(get("/api/v1/capabilities")).status).toBe(200)
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

describe("createMockServer and the waits", () => {
    it("refuses an answer that is not a body, naming no action", () => {
        const answer = createMockServer().handle({method: "POST", path: "/api/v1/waits/0b1f6c1e-0000-4000-8000-000000000003/answer", query: {}, body: {}})
        expect(answer.status).toBe(400)
    })

    it("ends the list at a cursor that left it", () => {
        const answer = createMockServer().handle({method: "GET", path: "/api/v1/waits", query: {before: "0b1f6c1e-0000-4000-8000-0000000000ff"}, body: undefined})
        expect(answer).toEqual({status: 200, body: {items: []}})
    })

    it("takes an answer that names no values", () => {
        const answer = createMockServer().handle({method: "POST", path: "/api/v1/waits/0b1f6c1e-0000-4000-8000-000000000002/answer", query: {}, body: {action: "yes"}})
        expect(answer).toMatchObject({status: 200, body: {status: "resolved", answer: {action: "yes", values: {}}}})
    })

    it("lists the open waits when no status is asked, and none for a status nobody has", () => {
        const listed = (query: Record<string, string>): number =>
            z.object({items: z.array(z.unknown())}).parse(createMockServer().handle({method: "GET", path: "/api/v1/waits", query, body: undefined}).body).items.length
        expect(listed({})).toBe(2)
        expect(listed({status: "expired"})).toBe(0)
    })

    it("names the organization like the other routes of the organization", () => {
        const answer = createMockServer({memberships: ["org_acme", "org_globex"]}).handle({method: "GET", path: "/api/v1/waits", query: {}, body: undefined})
        expect(answer.status).toBe(409)
    })
})
