// SPDX-License-Identifier: AGPL-3.0-only
import type {z} from "zod"
import {zCapabilities} from "@/api/gen/zod.gen"

/** `GET /api/v1/capabilities`: the API version a server speaks, its release and the features it serves. */
export const CapabilitiesSchema = zCapabilities
export type Capabilities = z.output<typeof zCapabilities>
