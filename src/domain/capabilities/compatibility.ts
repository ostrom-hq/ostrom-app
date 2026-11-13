// SPDX-License-Identifier: AGPL-3.0-only
import type {Capabilities} from "@/api/contract/capabilities"
import {ApiError} from "@/api/http/ApiError"

/** The version of the `/api/v1` contract this app speaks. A server that speaks another one is refused. */
export const SUPPORTED_API_VERSION = 1

/**
 * The features of the server this app has a function for (`ServerFeatures` in ostrom-core). A key the app does not list
 * here is ignored, as the contract asks.
 */
export const FEATURES = {
    /** Registering this phone for push. */
    push: "notifications.push.v1",
    /** The notification preferences. */
    notifications: "notifications.v1",
    /** The history of signed receipts. */
    receipts: "receipts.v1",
    /** The spending against budgets. */
    budgets: "budgets.v1",
    /** The waits asked of the person ("Waiting for you"). */
    waits: "waits.v1",
} as const
export type Feature = (typeof FEATURES)[keyof typeof FEATURES]

export type Compatibility =
    /** The server speaks this app's API version. */
    | {readonly kind: "compatible"; readonly capabilities: Capabilities}
    /** It speaks another one; `apiVersion` is undefined for a server too old to answer (404). */
    | {readonly kind: "incompatible"; readonly apiVersion: number | undefined}
    /** Not known now (network, timeout, server error, bad answer): never a reason to block or to sign out. */
    | {readonly kind: "unknown"}

/** The route does not exist: a server older than the capabilities contract. */
function isMissingRoute(error: unknown): boolean {
    if (!(error instanceof ApiError)) return false
    const {problem} = error
    return (problem.kind === "refused" || problem.kind === "unexpected-status") && problem.status === 404
}

/**
 * Reads what a server speaks and decides whether this app may talk to it. A 404 means a server older than the route:
 * it is treated as too old, since it cannot say which contract it speaks. Any other failure is `unknown`.
 */
export async function assessServer(read: () => Promise<Capabilities>): Promise<Compatibility> {
    try {
        const capabilities = await read()
        return capabilities.apiVersion === SUPPORTED_API_VERSION
            ? {kind: "compatible", capabilities}
            : {kind: "incompatible", apiVersion: capabilities.apiVersion}
    } catch (error) {
        return isMissingRoute(error) ? {kind: "incompatible", apiVersion: undefined} : {kind: "unknown"}
    }
}

/**
 * Whether a function is shown. Features not known yet (never read, the server unreachable since) hide nothing: a phone
 * offline keeps every function it had.
 */
export function isServed(capabilities: Capabilities | undefined, feature: Feature): boolean {
    return capabilities === undefined || capabilities.features.includes(feature)
}
