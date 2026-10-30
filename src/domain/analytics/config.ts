// SPDX-License-Identifier: AGPL-3.0-only
import {DEFAULT_HOST} from "./analytics"

// Expo inlines `process.env.EXPO_PUBLIC_*` at build time, and only as a literal member access: never read it through
// a variable or a bracket, or a release build sees nothing.
declare const process: {
    readonly env: {readonly EXPO_PUBLIC_POSTHOG_KEY?: string; readonly EXPO_PUBLIC_POSTHOG_HOST?: string}
}

export interface AnalyticsConfig {
    readonly apiKey: string
    readonly host: string
}

/**
 * The analytics destination compiled into the build (`EXPO_PUBLIC_POSTHOG_KEY`, `EXPO_PUBLIC_POSTHOG_HOST`, inlined by
 * Expo). Absent in a self-hosted or local build, which then has no analytics.
 */
export function readAnalyticsConfig(env: Readonly<Record<string, string | undefined>>): AnalyticsConfig {
    const host = env["EXPO_PUBLIC_POSTHOG_HOST"]?.trim()
    return {
        apiKey: env["EXPO_PUBLIC_POSTHOG_KEY"]?.trim() ?? "",
        host: host === undefined || host === "" ? DEFAULT_HOST : host,
    }
}

/** The configuration compiled into this build. */
export function buildAnalyticsConfig(): AnalyticsConfig {
    return readAnalyticsConfig({
        EXPO_PUBLIC_POSTHOG_KEY: process.env.EXPO_PUBLIC_POSTHOG_KEY,
        EXPO_PUBLIC_POSTHOG_HOST: process.env.EXPO_PUBLIC_POSTHOG_HOST,
    })
}
