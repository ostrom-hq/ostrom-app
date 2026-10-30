// SPDX-License-Identifier: AGPL-3.0-only
import type {AppServices} from "@/services/AppServices"
import {createAppServices} from "./services"

/**
 * The services of this build. The E2E build resolves this module to `buildServicesE2e.ts` instead (`metro.config.js`),
 * so no other build bundles the in-memory server.
 */
export function servicesForBuild(): AppServices {
    return createAppServices()
}
