// SPDX-License-Identifier: AGPL-3.0-only
import type {AppServices} from "@/services/AppServices"
import {createMockAppServices} from "./mockServices"

/** The services of the E2E build (`EXPO_PUBLIC_OSTROM_MOCK=1`, see `metro.config.js`): the in-memory server's. */
export function servicesForBuild(): AppServices {
    return createMockAppServices()
}
