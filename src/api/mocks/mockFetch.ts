// SPDX-License-Identifier: AGPL-3.0-only
import type {MockServer} from "./mockServer"

/** A body read as JSON, or nothing when the call has none. */
function jsonOf(text: string): unknown {
    return text === "" ? undefined : JSON.parse(text)
}

/**
 * A `fetch` answered by the in-memory server, without any network: what the E2E build talks to, since no Ostrom
 * server runs on the CI runner. The call is read as a `Request`, whatever form it came in.
 */
export function mockFetch(server: MockServer): typeof fetch {
    return async (input, init) => {
        const request = new Request(input, init)
        const url = new URL(request.url)
        const answer = server.handle({
            method: request.method,
            path: url.pathname,
            query: Object.fromEntries(url.searchParams),
            body: jsonOf(await request.text()),
        })
        return answer.status === 204
            ? new Response(null, {status: 204})
            : new Response(JSON.stringify(answer.body), {status: answer.status, headers: {"content-type": "application/json"}})
    }
}
