// SPDX-License-Identifier: AGPL-3.0-only
import type {AppServices} from "@/services/AppServices"
import {createMockAppServices} from "./mockServices"
import {createAppServices} from "./services"

/** The services of this build: the real ones, or the in-memory server's for the E2E build. */
export function servicesForBuild(mock: boolean): AppServices {
    return mock ? createMockAppServices() : createAppServices()
}
