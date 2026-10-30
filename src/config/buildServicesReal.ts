// SPDX-License-Identifier: AGPL-3.0-only
import type {AppServices} from "@/services/AppServices"
import {createRealServerAppServices, realServer} from "./realServices"

// Expo inlines `process.env.EXPO_PUBLIC_*` at build time; only this exact expression is replaced. The declaration is
// erased at compile time, so it leaves no binding for the inlining to trip on.
declare const process: {
    readonly env: {
        readonly EXPO_PUBLIC_OSTROM_E2E_SERVER?: string | undefined
        readonly EXPO_PUBLIC_OSTROM_E2E_TOKEN?: string | undefined
        readonly EXPO_PUBLIC_OSTROM_E2E_LOGIN?: string | undefined
    }
}

/** The services of the E2E build against a real server (`EXPO_PUBLIC_OSTROM_REAL_E2E=1`, see `metro.config.js`). */
export function servicesForBuild(): AppServices {
    // Read one by one, by name: Expo inlines each EXPO_PUBLIC_ variable at build time.
    return createRealServerAppServices(
        realServer({
            serverUrl: process.env.EXPO_PUBLIC_OSTROM_E2E_SERVER,
            token: process.env.EXPO_PUBLIC_OSTROM_E2E_TOKEN,
            login: process.env.EXPO_PUBLIC_OSTROM_E2E_LOGIN,
        }),
    )
}
