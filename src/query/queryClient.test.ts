// SPDX-License-Identifier: AGPL-3.0-only
import {focusManager} from "@tanstack/react-query"
import {AppState, type AppStateStatus} from "react-native"
import {ApiError} from "@/api/http/ApiError"
import {createQueryClient, followAppFocus, isUnauthorized, shouldRetry} from "./queryClient"

describe("shouldRetry", () => {
    it.each([
        [new ApiError({kind: "network"}), true],
        [new ApiError({kind: "timeout"}), true],
        [new ApiError({kind: "server", status: 502}), true],
        [new ApiError({kind: "refused", status: 403, type: "self_approval_refused", message: ""}), false],
        [new ApiError({kind: "unauthorized"}), false],
        [new ApiError({kind: "invalid-response", detail: ""}), false],
        [new Error("bug"), false],
    ])("retries %s: %s", (error, expected) => {
        expect(shouldRetry(0, error)).toBe(expected)
    })

    it("gives up after two retries", () => {
        expect(shouldRetry(2, new ApiError({kind: "network"}))).toBe(false)
    })
})

describe("createQueryClient", () => {
    it("signs out when a query or a mutation meets an expired token", async () => {
        const onUnauthorized = jest.fn()
        const client = createQueryClient({onUnauthorized})
        await client
            .fetchQuery({queryKey: ["a"], queryFn: () => Promise.reject(new ApiError({kind: "unauthorized"})), retry: false})
            .catch(() => undefined)
        expect(onUnauthorized).toHaveBeenCalledTimes(1)
        await client
            .getMutationCache()
            .build(client, {mutationFn: () => Promise.reject(new ApiError({kind: "unauthorized"}))})
            .execute(undefined)
            .catch(() => undefined)
        expect(onUnauthorized).toHaveBeenCalledTimes(2)
        await client
            .fetchQuery({queryKey: ["b"], queryFn: () => Promise.reject(new ApiError({kind: "network"})), retry: false})
            .catch(() => undefined)
        expect(onUnauthorized).toHaveBeenCalledTimes(2)
        expect(isUnauthorized(new Error("x"))).toBe(false)
        client.clear()
    })

    it("ignores a 401 of a request the sign-out already dropped", async () => {
        const onUnauthorized = jest.fn()
        const client = createQueryClient({onUnauthorized, retry: false})
        // A mutation of the previous account, still in flight when sign-out clears the cache, answered 401 after.
        const mutation = client.getMutationCache().build(client, {
            mutationFn: () =>
                new Promise((_resolve, reject) => {
                    setTimeout(() => {
                        reject(new ApiError({kind: "unauthorized"}))
                    }, 0)
                }),
        })
        const sent = mutation.execute(undefined)
        client.clear()
        await sent.catch(() => undefined)
        expect(onUnauthorized).not.toHaveBeenCalled()
    })

    it("retries by policy, or not at all for tests", () => {
        const retrying = createQueryClient({onUnauthorized: jest.fn()})
        expect(retrying.getDefaultOptions().queries?.retry).toBe(shouldRetry)
        const strict = createQueryClient({onUnauthorized: jest.fn(), retry: false})
        expect(strict.getDefaultOptions().queries?.retry).toBe(false)
    })
})

describe("followAppFocus", () => {
    it("reads the app coming back to the foreground as the focus", () => {
        let listener: (status: AppStateStatus) => void = () => undefined
        const remove = jest.fn()
        const subscribe = jest.spyOn(AppState, "addEventListener").mockImplementation((_type, handler) => {
            listener = handler
            return {remove}
        })
        const stop = followAppFocus()
        listener("background")
        expect(focusManager.isFocused()).toBe(false)
        listener("active")
        expect(focusManager.isFocused()).toBe(true)
        stop()
        expect(remove).toHaveBeenCalledTimes(1)
        subscribe.mockRestore()
        focusManager.setFocused(undefined)
    })
})
