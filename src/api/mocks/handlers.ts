import {http, HttpResponse, type HttpHandler} from "msw"
import * as fixtures from "./fixtures"
import {createMockServer} from "./mockServer"

/** A body read as JSON, or nothing when the request has none. */
function jsonOf(text: string): unknown {
    return text === "" ? undefined : JSON.parse(text)
}

/**
 * The in-memory Ostrom server of `mockServer.ts` behind msw, for the tests. Each call gives a fresh state, so an
 * answer in one test never leaks into another.
 */
export function createMockApi(baseUrl: string = fixtures.MOCK_BASE_URL): {handlers: HttpHandler[]} {
    const server = createMockServer()
    const handlers = [
        http.all(`${baseUrl}/api/v1/*`, async ({request}) => {
            const url = new URL(request.url)
            const answer = server.handle({
                method: request.method,
                path: url.pathname,
                query: Object.fromEntries(url.searchParams),
                body: jsonOf(await request.text()),
            })
            return answer.status === 204
                ? new HttpResponse(null, {status: 204})
                : new HttpResponse(JSON.stringify(answer.body), {status: answer.status, headers: {"content-type": "application/json"}})
        }),
    ]
    return {handlers}
}
