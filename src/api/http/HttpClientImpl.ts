import {z} from "zod"
import {zApiError} from "@/api/gen/zod.gen"
import {ApiError} from "./ApiError"
import type {HttpClient, HttpRequest} from "./HttpClient"

export interface HttpClientConfig {
    /** The server origin, e.g. `https://ostrom.acme.dev`, without a trailing slash. */
    readonly baseUrl: string
    /** The session token, read at each call so a sign-in or a sign-out takes effect at once. */
    readonly token: () => string | undefined
    readonly fetch?: typeof fetch
    /** How long to wait for an answer (headers and body) before giving up with `timeout`. */
    readonly timeoutMs?: number
}

export const DEFAULT_TIMEOUT_MS = 15_000

/**
 * Encodes one path segment (an id) for `HttpRequest.path`.
 * `.` and `..` are refused: URL parsing would resolve them and send the call (and the token) to another route.
 */
export function segment(value: string): string {
    if (value === "" || value === "." || value === "..") throw new RangeError(`not a path segment: "${value}"`)
    return encodeURIComponent(value)
}

export class HttpClientImpl implements HttpClient {
    constructor(private readonly config: HttpClientConfig) {}

    async send<S extends z.ZodType>(request: HttpRequest<S>): Promise<z.output<S>> {
        const {status, ok, body} = await this.exchange(request)
        if (!ok) throw problemOf(status, body)
        const parsed = request.response.safeParse(body)
        if (!parsed.success) {
            throw new ApiError({kind: "invalid-response", detail: z.prettifyError(parsed.error)}, {cause: parsed.error})
        }
        return parsed.data
    }

    /** Sends the request and reads the whole answer, under one timeout; transport failures become `ApiError`. */
    private async exchange<S extends z.ZodType>(
        request: HttpRequest<S>,
    ): Promise<{status: number; ok: boolean; body: unknown}> {
        const url = new URL(request.path, this.config.baseUrl)
        for (const [key, value] of Object.entries(request.query ?? {})) url.searchParams.set(key, value)
        const headers: Record<string, string> = {accept: "application/json"}
        const token = this.config.token()
        if (token !== undefined) headers["authorization"] = `Bearer ${token}`
        const controller = new AbortController()
        const init: RequestInit = {method: request.method, headers, signal: controller.signal}
        if (request.body !== undefined) {
            headers["content-type"] = "application/json"
            init.body = JSON.stringify(request.body)
        }
        const timer = setTimeout(() => {
            controller.abort()
        }, this.config.timeoutMs ?? DEFAULT_TIMEOUT_MS)
        try {
            const response = await this.fetch(url.toString(), init)
            return {status: response.status, ok: response.ok, body: parseJson(await response.text())}
        } catch (cause) {
            throw new ApiError(controller.signal.aborted ? {kind: "timeout"} : {kind: "network"}, {cause})
        } finally {
            clearTimeout(timer)
        }
    }

    /** The global `fetch` is read at each call, not at construction: polyfills and test interceptors replace it. */
    private fetch(input: string, init: RequestInit): Promise<Response> {
        const {fetch: custom} = this.config
        return custom === undefined ? globalThis.fetch(input, init) : custom(input, init)
    }
}

const NOT_JSON = Symbol("not JSON")

/** The JSON body, `undefined` when empty, `NOT_JSON` when it is not JSON (no schema accepts a symbol). */
function parseJson(text: string): unknown {
    if (text === "") return undefined
    try {
        return JSON.parse(text)
    } catch {
        return NOT_JSON
    }
}

function problemOf(status: number, body: unknown): ApiError {
    const error = zApiError.safeParse(body)
    if (status === 401) return new ApiError(error.success ? {kind: "unauthorized", type: error.data.error.type} : {kind: "unauthorized"})
    if (status >= 500) return new ApiError({kind: "server", status})
    if (!error.success) return new ApiError({kind: "unexpected-status", status})
    return new ApiError({kind: "refused", status, type: error.data.error.type, message: error.data.error.message})
}
