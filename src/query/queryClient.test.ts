import {ApiError} from "@/api/http/ApiError"
import {createQueryClient, isUnauthorized, shouldRetry} from "./queryClient"

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

    it("retries by policy, or not at all for tests", () => {
        const retrying = createQueryClient({onUnauthorized: jest.fn()})
        expect(retrying.getDefaultOptions().queries?.retry).toBe(shouldRetry)
        const strict = createQueryClient({onUnauthorized: jest.fn(), retry: false})
        expect(strict.getDefaultOptions().queries?.retry).toBe(false)
    })
})
