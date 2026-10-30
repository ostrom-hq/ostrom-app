import {z} from "zod"
import {ErrorBodySchema} from "@/api/contract/common"
import {ApiError} from "./ApiError"
import type {HttpClient, HttpRequest} from "./HttpClient"

export interface HttpClientConfig {
    /** The server origin, e.g. `https://ostrom.acme.dev`, without a trailing slash. */
    readonly baseUrl: string
    /** The session token, read at each call so a sign-in or a sign-out takes effect at once. */
    readonly token: () => string | undefined
    readonly fetch?: typeof fetch
}

/** Encodes one path segment (an id) for `HttpRequest.path`. */
export function segment(value: string): string {
    return encodeURIComponent(value)
}

export class HttpClientImpl implements HttpClient {
    constructor(private readonly config: HttpClientConfig) {}

    async send<S extends z.ZodType>(request: HttpRequest<S>): Promise<z.output<S>> {
        const response = await this.call(request)
        const body = await readJson(response)
        if (!response.ok) throw problemOf(response.status, body)
        const parsed = request.response.safeParse(body)
        if (!parsed.success) {
            throw new ApiError({kind: "invalid-response", detail: z.prettifyError(parsed.error)}, {cause: parsed.error})
        }
        return parsed.data
    }

    private async call<S extends z.ZodType>(request: HttpRequest<S>): Promise<Response> {
        const url = new URL(request.path, this.config.baseUrl)
        for (const [key, value] of Object.entries(request.query ?? {})) url.searchParams.set(key, value)
        const headers: Record<string, string> = {accept: "application/json"}
        const token = this.config.token()
        if (token !== undefined) headers["authorization"] = `Bearer ${token}`
        const init: RequestInit = {method: request.method, headers}
        if (request.body !== undefined) {
            headers["content-type"] = "application/json"
            init.body = JSON.stringify(request.body)
        }
        try {
            return await this.fetch(url.toString(), init)
        } catch (cause) {
            throw new ApiError({kind: "network"}, {cause})
        }
    }

    /** The global `fetch` is read at each call, not at construction: polyfills and test interceptors replace it. */
    private fetch(input: string, init: RequestInit): Promise<Response> {
        return this.config.fetch === undefined ? globalThis.fetch(input, init) : this.config.fetch(input, init)
    }
}

const NO_BODY = Symbol("no body")

/** The JSON body, `undefined` when empty, `NO_BODY` when it is not JSON. */
async function readJson(response: Response): Promise<unknown> {
    const text = await response.text()
    if (text === "") return undefined
    try {
        return JSON.parse(text)
    } catch {
        return NO_BODY
    }
}

function problemOf(status: number, body: unknown): ApiError {
    if (status === 401) return new ApiError({kind: "unauthorized"})
    const error = ErrorBodySchema.safeParse(body)
    if (status < 500 && error.success) {
        return new ApiError({kind: "refused", status, type: error.data.error.type, message: error.data.error.message})
    }
    return new ApiError({kind: "server", status})
}
