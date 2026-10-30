// SPDX-License-Identifier: AGPL-3.0-only
import {normalizeServerUrl} from "@/ui/screens/login/serverUrl"

// Expo inlines `process.env.EXPO_PUBLIC_*` at build time; only this exact expression is replaced. The declaration is
// erased at compile time, so it leaves no binding for the inlining to trip on.
declare const process: {readonly env: {readonly EXPO_PUBLIC_OSTROM_API_URL?: string | undefined}}

const HOSTED_SERVER_URL = "https://app.ostrom.run"

/**
 * The server the login screen proposes: the one the build was made for, read like an address typed by a person, else
 * the hosted Ostrom (unset, blank, or not a server address).
 */
export function serverUrlFrom(configured: string | undefined): string {
    return (configured === undefined ? undefined : normalizeServerUrl(configured)) ?? HOSTED_SERVER_URL
}

export const DEFAULT_SERVER_URL = serverUrlFrom(process.env.EXPO_PUBLIC_OSTROM_API_URL)
