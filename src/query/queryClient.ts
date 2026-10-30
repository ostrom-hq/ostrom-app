// SPDX-License-Identifier: AGPL-3.0-only
import {focusManager, MutationCache, QueryCache, QueryClient} from "@tanstack/react-query"
import {AppState} from "react-native"
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

/**
 * The server state cache. Mutations are never retried: a decision is sent once, then read back.
 * A 401 signs out only when it comes from a query or a mutation still in the cache: a request of the previous account,
 * answered after sign-out cleared the cache and someone signed in again, never signs the new one out.
 */
export function createQueryClient({onUnauthorized, retry = true}: QueryClientOptions): QueryClient {
    const queryCache: QueryCache = new QueryCache({
        onError: (error, query) => {
            if (isUnauthorized(error) && queryCache.get(query.queryHash) === query) onUnauthorized()
        },
    })
    const mutationCache: MutationCache = new MutationCache({
        onError: (error, _variables, _context, mutation) => {
            if (isUnauthorized(error) && mutationCache.getAll().some((cached) => cached === mutation)) onUnauthorized()
        },
    })
    return new QueryClient({
        queryCache,
        mutationCache,
        defaultOptions: {
            queries: {retry: retry ? shouldRetry : false, staleTime: 10_000},
            mutations: {retry: false},
        },
    })
}

/**
 * React Native has no window focus: the app coming back to the foreground is the focus, so the inbox is read again
 * then (`refetchOnWindowFocus`). Returns the unsubscription.
 */
export function followAppFocus(): () => void {
    const subscription = AppState.addEventListener("change", (status) => {
        focusManager.setFocused(status === "active")
    })
    return () => {
        subscription.remove()
    }
}
