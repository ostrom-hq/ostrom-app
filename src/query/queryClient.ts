import {MutationCache, QueryCache, QueryClient} from "@tanstack/react-query"
import {ApiError} from "@/api/http/ApiError"

const MAX_RETRIES = 2

/** Only a failure that may pass on its own is retried: never a refusal, a bad token or a broken contract. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
    if (failureCount >= MAX_RETRIES || !(error instanceof ApiError)) return false
    const {kind} = error.problem
    return kind === "network" || kind === "timeout" || kind === "server"
}

export function isUnauthorized(error: unknown): boolean {
    return error instanceof ApiError && error.problem.kind === "unauthorized"
}

export interface QueryClientOptions {
    /** Called when the server says the token is no longer valid: the app signs out. */
    readonly onUnauthorized: () => void
    /** Tests turn retries off to see a failure at once. */
    readonly retry?: boolean
}

/** The server state cache. Mutations are never retried: a decision is sent once, then read back. */
export function createQueryClient({onUnauthorized, retry = true}: QueryClientOptions): QueryClient {
    const onError = (error: unknown): void => {
        if (isUnauthorized(error)) onUnauthorized()
    }
    return new QueryClient({
        queryCache: new QueryCache({onError}),
        mutationCache: new MutationCache({onError}),
        defaultOptions: {
            queries: {retry: retry ? shouldRetry : false, staleTime: 10_000},
            mutations: {retry: false},
        },
    })
}
