// SPDX-License-Identifier: AGPL-3.0-only
/**
 * The requests for a yes (`Approval`), as the app reads them. The shapes are the contract's, generated from
 * ostrom-core's `api/openapi.yaml` into `@/api/gen`; this file only narrows what a phone must tolerate.
 */
import {z} from "zod"
import {
    zApproval,
    zApprovalAction,
    zApprovalActionKind,
    zApprovalActionRisk,
    zApprovalCallerView,
    zApprovalCheck,
    zApprovalQueue,
    zApprovalStatus,
    zApprovalView,
    zCheckState,
    zGateRef,
} from "@/api/gen/zod.gen"

/**
 * A value of a newer server, outside the list `schema` knows, reads as `fallback` instead of failing the request: the
 * person approves on this evidence, with an app that may be months old.
 */
function orElse<T extends z.ZodType<string>, F extends string>(
    schema: T,
    fallback: F,
): z.ZodUnion<readonly [T, z.ZodPipe<z.ZodString, z.ZodTransform<F, string>>]> {
    return z.union([schema, z.string().transform((): F => fallback)])
}

/** A check state this app does not know reads as `unknown`, never as a pass. */
export const CheckStateSchema = orElse(zCheckState, "unknown")
export type CheckState = z.output<typeof CheckStateSchema>

export const ApprovalSchema = zApproval.extend({
    // A status from a newer server reads as `unknown`: the request is shown, never answered from this app.
    status: orElse(zApprovalStatus, "unknown"),
    checks: z.array(zApprovalCheck.extend({state: CheckStateSchema})).optional(),
    // An unknown kind makes the button say only "Approve"; an unknown risk asks for more (biometrics), never for less.
    action: zApprovalAction.extend({kind: orElse(zApprovalActionKind, "unknown"), risk: orElse(zApprovalActionRisk, "high")}).optional(),
})
export type Approval = z.output<typeof ApprovalSchema>
export type ApprovalStatus = Approval["status"]
export type ActionKind = NonNullable<Approval["action"]>["kind"]

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

/**
 * `GET /api/v1/approvals/{id}/view`, and the answer of `POST …/answer`: a request as the console and the app show it.
 * Only what the phone shows is read (who asks, the command, the gate, the note, what the caller may do), so a rule or
 * a receipt of a newer shape never keeps the request from showing.
 */
export const ApprovalViewSchema = zApprovalView.pick({who: true, what: true, note: true}).extend({
    approval: ApprovalSchema,
    gate: zGateRef.pick({workflowName: true, label: true}).optional(),
    you: zApprovalCallerView,
})
export type ApprovalView = z.output<typeof ApprovalViewSchema>

/** `GET /api/v1/approvals/queue`: what waits for the caller, and what was answered lately. */
export const ApprovalQueueSchema = zApprovalQueue.pick({operator: true, you: true}).extend({
    pending: z.array(ApprovalViewSchema),
    recent: z.array(ApprovalViewSchema),
})
export type ApprovalQueue = z.output<typeof ApprovalQueueSchema>

export type {AnswerApprovalRequest as AnswerRequest, ApprovalAnswer} from "@/api/gen"
