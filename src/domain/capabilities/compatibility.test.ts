// SPDX-License-Identifier: AGPL-3.0-only
import type {Capabilities} from "@/api/contract/capabilities"
import {ApiError} from "@/api/http/ApiError"
import {assessServer, FEATURES, isServed, SUPPORTED_API_VERSION} from "./compatibility"

const speaking = (apiVersion: number): Capabilities => ({apiVersion, serverVersion: "1.4.0", features: ["receipts.v1"]})

describe("assessServer", () => {
    it("accepts a server that speaks this app's API version", async () => {
        const capabilities = speaking(SUPPORTED_API_VERSION)
        await expect(assessServer(() => Promise.resolve(capabilities))).resolves.toEqual({kind: "compatible", capabilities})
    })

    it("refuses a server that speaks another version, and says which", async () => {
        await expect(assessServer(() => Promise.resolve(speaking(2)))).resolves.toEqual({kind: "incompatible", apiVersion: 2})
    })

    it.each([
        ["a refusal", new ApiError({kind: "refused", status: 404, type: "not_found", message: "No such route"})],
        ["a bare 404", new ApiError({kind: "unexpected-status", status: 404})],
    ])("treats %s of the route as a server too old to say", async (_name: string, error: ApiError) => {
        await expect(assessServer(() => Promise.reject(error))).resolves.toEqual({kind: "incompatible", apiVersion: undefined})
    })

    it.each([
        ["no network", new ApiError({kind: "network"})],
        ["a timeout", new ApiError({kind: "timeout"})],
        ["a server error", new ApiError({kind: "server", status: 503})],
        ["another refusal", new ApiError({kind: "refused", status: 403, type: "forbidden", message: "No"})],
        ["another status", new ApiError({kind: "unexpected-status", status: 502})],
        ["a bad token", new ApiError({kind: "unauthorized"})],
        ["an answer off the contract", new ApiError({kind: "invalid-response", detail: "apiVersion"})],
        ["a failure that is not the API's", new Error("boom")],
    ])("knows nothing from %s, and never refuses for it", async (_name: string, error: Error) => {
        await expect(assessServer(() => Promise.reject(error))).resolves.toEqual({kind: "unknown"})
    })

    it("knows nothing when the read fails at once", async () => {
        await expect(
            assessServer(() => {
                throw new Error("no repository")
            }),
        ).resolves.toEqual({kind: "unknown"})
    })
})

describe("isServed", () => {
    it("serves a feature the server lists, and hides one it does not", () => {
        expect(isServed(speaking(1), FEATURES.receipts)).toBe(true)
        expect(isServed(speaking(1), FEATURES.push)).toBe(false)
    })

    it("hides nothing while the features are not known", () => {
        expect(isServed(undefined, FEATURES.push)).toBe(true)
    })
})
