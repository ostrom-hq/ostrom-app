// SPDX-License-Identifier: AGPL-3.0-only
import type {MockServer} from "./mockServer"

/** The address of a call, whatever form `fetch` was given it in. */
function urlOf(input: string | URL | Request): URL {
    if (typeof input === "string") return new URL(input)
    return input instanceof URL ? input : new URL(input.url)
}

/**
 * A `fetch` answered by the in-memory server, without any network: what the E2E build talks to, since no Ostrom
 * server runs on the CI runner. Only the JSON bodies the app sends are read.
 */
export function mockFetch(server: MockServer): typeof fetch {
    return (input, init) => {
        const url = urlOf(input)
        const raw = init?.body
        const answer = server.handle({
            method: init?.method ?? "GET",
            path: url.pathname,
            query: Object.fromEntries(url.searchParams),
            body: typeof raw === "string" ? JSON.parse(raw) : undefined,
        })
        return Promise.resolve(
            answer.status === 204
                ? new Response(null, {status: 204})
                : new Response(JSON.stringify(answer.body), {status: answer.status, headers: {"content-type": "application/json"}}),
        )
    }
}
