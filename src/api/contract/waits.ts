// SPDX-License-Identifier: AGPL-3.0-only
/**
 * The waits ("Waiting for you"), as the app reads them. The shapes are the contract's, generated from ostrom-core's
 * `api/openapi.yaml` into `@/api/gen`; this file only narrows what a phone must tolerate.
 */
import {z} from "zod"
import {zListWaitsResponse, zWait, zWaitStatus} from "@/api/gen/zod.gen"
import {orElse} from "./orElse"

/** A status from a newer server reads as `unknown`: the wait is shown, never answered from this app. */
export const WaitStatusSchema = orElse(zWaitStatus, "unknown")

export const WaitSchema = zWait.extend({status: WaitStatusSchema})
export type Wait = z.output<typeof WaitSchema>
export type WaitStatus = Wait["status"]
export type WaitSpec = Wait["question"]
export type WaitField = NonNullable<WaitSpec["fields"]>[number]
export type WaitRef = NonNullable<WaitSpec["refs"]>[number]
export type WaitAction = NonNullable<WaitSpec["actions"]>[number]

/** `GET /api/v1/waits`: newest first, `next` is the cursor (the id of the last wait) of the older page. */
export const WaitsPageSchema = zListWaitsResponse.extend({items: z.array(WaitSchema)})
export type WaitsPage = z.output<typeof WaitsPageSchema>

/** The values of an answer, by field id (`POST /api/v1/waits/{id}/answer`). */
export type WaitValues = Readonly<Record<string, unknown>>

/** What the app lists: `status` of the contract, for what waits, what was answered and what was withdrawn. */
export type WaitFilter = "open" | "answered" | "cancelled"

/** The `status` query value of each filter. */
export const WAIT_FILTER_STATUS: Readonly<Record<WaitFilter, "open" | "resolved" | "cancelled">> = {
    open: "open",
    answered: "resolved",
    cancelled: "cancelled",
}
