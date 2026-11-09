// SPDX-License-Identifier: AGPL-3.0-only
import type {Capabilities} from "@/api/contract/capabilities"

export interface CapabilitiesRepository {
    /** What the server speaks and serves, for the session of this repository's token. */
    read(): Promise<Capabilities>
}
