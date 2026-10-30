// SPDX-License-Identifier: AGPL-3.0-only
/**
 * The requests for a yes (`Approval`), as the app reads them. The shapes are the contract's, generated from
 * ostrom-core's `api/openapi.yaml` into `@/api/gen`; this file only narrows what a phone must tolerate.
 */
import {z} from "zod"
import {zAnswerApprovalResponse, zApproval, zApprovalCheck, zCheckState, zListApprovalsResponse} from "@/api/gen/zod.gen"

/**
 * A check state this app does not know (from a newer server) reads as `unknown`, never as a pass and never as a
 * request that cannot be shown: the person approves on this evidence, with an app that may be months old.
 */
export const CheckStateSchema = z.union([zCheckState, z.string().transform((): "unknown" => "unknown")])
export type CheckState = z.output<typeof CheckStateSchema>

export const ApprovalSchema = zApproval.extend({
    checks: z.array(zApprovalCheck.extend({state: CheckStateSchema})).optional(),
})
export type Approval = z.output<typeof ApprovalSchema>
export type ApprovalStatus = Approval["status"]

/** `GET /api/v1/approvals?mine=true&status=pending`: what waits for the caller. */
export const ApprovalPageSchema = zListApprovalsResponse.extend({items: z.array(ApprovalSchema)})
export type ApprovalPage = z.output<typeof ApprovalPageSchema>

/** `POST /api/v1/approvals/{id}/answer`: the answered request. Its receipt is read from the history, not here. */
export const AnswerResultSchema = zAnswerApprovalResponse.omit({receipt: true}).extend({approval: ApprovalSchema})
export type AnswerResult = z.output<typeof AnswerResultSchema>

export type {AnswerApprovalRequest as AnswerRequest, ApprovalAnswer} from "@/api/gen"
