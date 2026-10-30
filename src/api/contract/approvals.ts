// SPDX-License-Identifier: AGPL-3.0-only
/**
 * The requests for a yes (`Approval`), as the app reads them. The shapes are the contract's, generated from
 * ostrom-core's `api/openapi.yaml` into `@/api/gen`; this file only narrows what a phone must tolerate.
 */
import {z} from "zod"
import {
    zAnswerApprovalResponse,
    zApproval,
    zApprovalAction,
    zApprovalActionKind,
    zApprovalActionRisk,
    zApprovalCheck,
    zApprovalStatus,
    zCheckState,
    zListApprovalsResponse,
} from "@/api/gen/zod.gen"

/**
 * A check state this app does not know (from a newer server) reads as `unknown`, never as a pass and never as a
 * request that cannot be shown: the person approves on this evidence, with an app that may be months old.
 */
export const CheckStateSchema = z.union([zCheckState, z.string().transform((): "unknown" => "unknown")])
export type CheckState = z.output<typeof CheckStateSchema>

/** A status from a newer server reads as `unknown`: the request is shown, never answered from this app. */
export const ApprovalStatusSchema = z.union([zApprovalStatus, z.string().transform((): "unknown" => "unknown")])

/** An action kind from a newer server reads as `unknown`: the button then says only "Approve". */
export const ActionKindSchema = z.union([zApprovalActionKind, z.string().transform((): "unknown" => "unknown")])
export type ActionKind = z.output<typeof ActionKindSchema>

/** A risk this app does not know reads as `high`: it asks for more (biometrics), never for less. */
export const ActionRiskSchema = z.union([zApprovalActionRisk, z.string().transform((): "high" => "high")])

export const ApprovalSchema = zApproval.extend({
    status: ApprovalStatusSchema,
    checks: z.array(zApprovalCheck.extend({state: CheckStateSchema})).optional(),
    action: zApprovalAction.extend({kind: ActionKindSchema, risk: ActionRiskSchema}).optional(),
})
export type Approval = z.output<typeof ApprovalSchema>
export type ApprovalStatus = Approval["status"]

/**
 * A mobile yes needs biometrics unless the contract says the action is low risk: a request without an action (an
 * older server) is treated as high risk (decision of 30/10 04:10, contract 0.1.129).
 */
export function needsBiometrics(approval: Approval): boolean {
    return approval.action?.risk !== "low"
}

/** The server does not show the whole change: the app refuses a yes and leaves it to the console. */
export function phoneMayApprove(approval: Approval): boolean {
    return approval.diffTruncated !== true
}

/** `GET /api/v1/approvals?mine=true&status=pending`: what waits for the caller. */
export const ApprovalPageSchema = zListApprovalsResponse.extend({items: z.array(ApprovalSchema)})
export type ApprovalPage = z.output<typeof ApprovalPageSchema>

/** `POST /api/v1/approvals/{id}/answer`: the answered request. Its receipt is read from the history, not here. */
export const AnswerResultSchema = zAnswerApprovalResponse.omit({receipt: true}).extend({approval: ApprovalSchema})
export type AnswerResult = z.output<typeof AnswerResultSchema>

export type {AnswerApprovalRequest as AnswerRequest, ApprovalAnswer} from "@/api/gen"
