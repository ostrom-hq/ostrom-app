import {DEFAULT_HOST} from "./analytics"

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
