import type {z} from "zod"

export type HttpMethod = "GET" | "POST" | "PUT" | "DELETE"

export interface HttpRequest<S extends z.ZodType> {
    readonly method: HttpMethod
    /** Path from the server root, starting with `/api/`. Path parameters are already encoded. */
    readonly path: string
    readonly query?: Readonly<Record<string, string>>
    readonly body?: unknown
    /** The schema the answer must match; the call fails with `invalid-response` otherwise. */
    readonly response: S
}

/** Talks to one Ostrom server: adds the token, maps failures to `ApiError`, parses every answer. */
export interface HttpClient {
    send<S extends z.ZodType>(request: HttpRequest<S>): Promise<z.output<S>>
}
