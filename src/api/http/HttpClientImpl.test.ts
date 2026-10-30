import {delay, http, HttpResponse} from "msw"
import {setupServer} from "msw/node"
import {z} from "zod"
import {ApiError, refusalType} from "./ApiError"
import {HttpClientImpl, segment} from "./HttpClientImpl"

const BASE = "https://ostrom.test"
const server = setupServer()

beforeAll(() => {
    server.listen({onUnhandledRequest: "error"})
})
afterEach(() => {
    server.resetHandlers()
})
afterAll(() => {
    server.close()
})

const Pong = z.object({pong: z.boolean()})

function client(token?: string): HttpClientImpl {
    return new HttpClientImpl({baseUrl: BASE, token: () => token})
}

async function problemOf(call: Promise<unknown>): Promise<ApiError["problem"]> {
    try {
        await call
    } catch (error) {
        if (error instanceof ApiError) return error.problem
        throw error
    }
    throw new Error("the call did not fail")
}

describe("HttpClientImpl", () => {
    it("sends the token, the query and the JSON body, and parses the answer", async () => {
        let seen: {auth: string | null; type: string | null; query: string | null; body: unknown} | undefined
        server.use(
            http.post(`${BASE}/api/ping`, async ({request}) => {
                seen = {
                    auth: request.headers.get("authorization"),
                    type: request.headers.get("content-type"),
                    query: new URL(request.url).searchParams.get("x"),
                    body: await request.json(),
                }
                return HttpResponse.json({pong: true})
            }),
        )
        const answer = await client("ost_1").send({
            method: "POST",
            path: "/api/ping",
            query: {x: "1"},
            body: {hello: "world"},
            response: Pong,
        })
        expect(answer).toEqual({pong: true})
        expect(seen).toEqual({auth: "Bearer ost_1", type: "application/json", query: "1", body: {hello: "world"}})
    })

    it("sends no authorization header, no organization and no body when there is none", async () => {
        let seen: {auth: string | null; type: string | null; org: string | null} | undefined
        server.use(
            http.get(`${BASE}/api/ping`, ({request}) => {
                seen = {auth: request.headers.get("authorization"), type: request.headers.get("content-type"), org: request.headers.get("ostrom-org")}
                return HttpResponse.json({pong: true})
            }),
        )
        await client().send({method: "GET", path: "/api/ping", response: Pong})
        await new HttpClientImpl({baseUrl: BASE, token: () => undefined, org: () => undefined}).send({method: "GET", path: "/api/ping", response: Pong})
        expect(seen).toEqual({auth: null, type: null, org: null})
    })

    it("names the organization the call acts in, read at each call", async () => {
        const seen: (string | null)[] = []
        server.use(
            http.get(`${BASE}/api/ping`, ({request}) => {
                seen.push(request.headers.get("ostrom-org"))
                return HttpResponse.json({pong: true})
            }),
        )
        let org = "org_acme"
        const http1 = new HttpClientImpl({baseUrl: BASE, token: () => "ost_1", org: () => org})
        await http1.send({method: "GET", path: "/api/ping", response: Pong})
        org = "org_beta"
        await http1.send({method: "GET", path: "/api/ping", response: Pong})
        expect(seen).toEqual(["org_acme", "org_beta"])
    })

    it("reads an empty answer as undefined", async () => {
        server.use(http.delete(`${BASE}/api/thing/1`, () => new HttpResponse(null, {status: 204})))
        await expect(client().send({method: "DELETE", path: "/api/thing/1", response: z.undefined()})).resolves.toBeUndefined()
    })

    it("uses the fetch it is given", async () => {
        const fetch = jest.fn(() => Promise.resolve(Response.json({pong: false})))
        const custom = new HttpClientImpl({baseUrl: BASE, token: () => undefined, fetch})
        await expect(custom.send({method: "GET", path: "/api/ping", response: Pong})).resolves.toEqual({pong: false})
        expect(fetch).toHaveBeenCalledWith(`${BASE}/api/ping`, expect.objectContaining({method: "GET"}))
    })

    it("asks to sign in again on 401", async () => {
        server.use(http.get(`${BASE}/api/ping`, () => new HttpResponse(null, {status: 401})))
        expect(await problemOf(client().send({method: "GET", path: "/api/ping", response: Pong}))).toEqual({
            kind: "unauthorized",
        })
    })

    it("reports a deliberate refusal with its stable type", async () => {
        server.use(
            http.get(`${BASE}/api/ping`, () =>
                HttpResponse.json({error: {type: "self_approval_refused", message: "ask someone else"}}, {status: 403}),
            ),
        )
        const call = client().send({method: "GET", path: "/api/ping", response: Pong})
        expect(await problemOf(call)).toEqual({
            kind: "refused",
            status: 403,
            type: "self_approval_refused",
            message: "ask someone else",
        })
    })

    it("keeps the server's key on a 401 that gives one", async () => {
        server.use(
            http.post(`${BASE}/api/ping`, () =>
                HttpResponse.json({error: {type: "invalid_code", message: "expired"}}, {status: 401}),
            ),
        )
        expect(await problemOf(client().send({method: "POST", path: "/api/ping", response: Pong}))).toEqual({
            kind: "unauthorized",
            type: "invalid_code",
        })
    })

    it("reports a server failure, and a 4xx without the contract's error body", async () => {
        server.use(
            http.get(`${BASE}/api/a`, () => HttpResponse.json({error: {type: "boom", message: "no"}}, {status: 500})),
            http.get(`${BASE}/api/b`, () => HttpResponse.text("Not Found", {status: 404})),
        )
        expect(await problemOf(client().send({method: "GET", path: "/api/a", response: Pong}))).toEqual({
            kind: "server",
            status: 500,
        })
        expect(await problemOf(client().send({method: "GET", path: "/api/b", response: Pong}))).toEqual({
            kind: "unexpected-status",
            status: 404,
        })
    })

    it("refuses an answer that is not JSON or does not match the contract", async () => {
        server.use(
            http.get(`${BASE}/api/a`, () => HttpResponse.text("<html>")),
            http.get(`${BASE}/api/b`, () => HttpResponse.json({pong: "yes"})),
        )
        expect(await problemOf(client().send({method: "GET", path: "/api/a", response: Pong}))).toMatchObject({
            kind: "invalid-response",
        })
        const mismatch = await problemOf(client().send({method: "GET", path: "/api/b", response: Pong}))
        expect(mismatch.kind === "invalid-response" ? mismatch.detail : "").toContain("pong")
    })

    it("gives up with a timeout when the server does not answer", async () => {
        server.use(
            http.get(`${BASE}/api/ping`, async () => {
                await delay("infinite")
                return HttpResponse.json({pong: true})
            }),
        )
        const slow = new HttpClientImpl({baseUrl: BASE, token: () => undefined, timeoutMs: 20})
        expect(await problemOf(slow.send({method: "GET", path: "/api/ping", response: Pong}))).toEqual({kind: "timeout"})
    })

    it("reports a connection that breaks while the answer is read", async () => {
        const broken = new ReadableStream({
            start(controller) {
                controller.error(new Error("connection reset"))
            },
        })
        const fetch = jest.fn(() => Promise.resolve(new Response(broken)))
        const custom = new HttpClientImpl({baseUrl: BASE, token: () => undefined, fetch})
        expect(await problemOf(custom.send({method: "GET", path: "/api/ping", response: Pong}))).toEqual({
            kind: "network",
        })
    })

    it("reports a network failure", async () => {
        server.use(http.get(`${BASE}/api/ping`, () => HttpResponse.error()))
        expect(await problemOf(client().send({method: "GET", path: "/api/ping", response: Pong}))).toEqual({
            kind: "network",
        })
    })
})

describe("segment", () => {
    it("encodes an id for a path", () => {
        expect(segment("a/b c")).toBe("a%2Fb%20c")
    })

    it("refuses what URL parsing would resolve to another route", () => {
        for (const value of ["", ".", ".."]) expect(() => segment(value)).toThrow(RangeError)
    })
})

describe("refusalType", () => {
    it("gives the stable key of a refusal only", () => {
        expect(refusalType(new ApiError({kind: "refused", status: 403, type: "x", message: "m"}))).toBe("x")
        expect(refusalType(new ApiError({kind: "network"}))).toBeUndefined()
        expect(refusalType(new Error("x"))).toBeUndefined()
    })
})
