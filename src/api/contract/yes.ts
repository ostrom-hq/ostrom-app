import {z} from "zod"
import {DateTimeSchema} from "./common"

export const AskStatusSchema = z.enum(["pending", "approved", "rejected", "expired"])
export type AskStatus = z.output<typeof AskStatusSchema>

export const EvidenceSchema = z.object({
    label: z.string(),
    value: z.string(),
    url: z.string().nullish(),
})
export type Evidence = z.output<typeof EvidenceSchema>

/** Proposed addition to the prototype's Ask: the exact commits the yes covers. */
export const CommitSchema = z.object({
    sha: z.string(),
    title: z.string(),
    author: z.string().nullish(),
    url: z.string().nullish(),
})
export type Commit = z.output<typeof CommitSchema>

export const CheckStateSchema = z.enum(["success", "failure", "pending", "skipped"]).catch("pending")
export type CheckState = z.output<typeof CheckStateSchema>

/** Proposed addition to the prototype's Ask: the CI checks of the head the yes covers. */
export const CheckSchema = z.object({
    name: z.string(),
    state: CheckStateSchema,
    url: z.string().nullish(),
})
export type Check = z.output<typeof CheckSchema>

export const AskRuleSchema = z.object({
    id: z.string(),
    statement: z.string(),
    strength: z.string().nullish(),
    status: z.string().nullish(),
})
export type AskRule = z.output<typeof AskRuleSchema>

/** The receipt of a decided request, as the server summarises it (the full receipt is fetched to verify it). */
export const AskReceiptSchema = z.object({
    id: z.string(),
    kind: z.string(),
    createdAt: DateTimeSchema,
    valid: z.boolean(),
    digest: z.string(),
    by: z.string().nullish(),
    result: z.string().nullish(),
    selfApproved: z.boolean().nullish(),
})

/** What the person looking can do with this request, computed by the server for them. */
export const YouSchema = z.object({
    login: z.string(),
    self: z.boolean(),
    canApprove: z.boolean(),
    reason: z.string().nullish(),
    selfApproval: z.boolean().nullish(),
})
export type You = z.output<typeof YouSchema>

/** A request for a person's yes, with everything shown to them (prototype `Ask`, mvp/server/src/yes/routes.ts). */
export const AskSchema = z.object({
    id: z.string(),
    status: AskStatusSchema,
    title: z.string(),
    requestedAt: DateTimeSchema.nullish(),
    decidedBy: z.string().nullish(),
    decidedAt: DateTimeSchema.nullish(),
    who: z.object({
        dev: z.string().nullish(),
        agent: z.string().nullish(),
        model: z.string().nullish(),
        session: z.string().nullish(),
    }),
    what: z.object({
        action: z.string().nullish(),
        command: z.string().nullish(),
        repo: z.string().nullish(),
        sessionRepo: z.string().nullish(),
        harness: z.string().nullish(),
    }),
    gate: z
        .object({
            label: z.string(),
            workflow: z.string().nullish(),
            workflowId: z.string().nullish(),
            approvers: z.array(z.string()),
        })
        .nullish(),
    rules: z.array(AskRuleSchema),
    diff: z.string().nullish(),
    evidence: z.array(EvidenceSchema),
    commits: z.array(CommitSchema).default([]),
    checks: z.array(CheckSchema).default([]),
    shownDigest: z.string(),
    receipt: AskReceiptSchema.nullish(),
    askedBy: z.array(z.string()),
    selfApproved: z.boolean().nullish(),
    askedVia: z.array(z.string()).nullish(),
    pr: z.object({number: z.string(), sha: z.string()}).nullish(),
    you: YouSchema.nullish(),
})
export type Ask = z.output<typeof AskSchema>

/** `GET /api/yes/queue`: what waits for the person, and the last decided. */
export const YesQueueSchema = z.object({
    operator: z.string(),
    pending: z.array(AskSchema),
    recent: z.array(AskSchema),
})
export type YesQueue = z.output<typeof YesQueueSchema>

export const DecisionSchema = z.enum(["approved", "rejected"])
export type Decision = z.output<typeof DecisionSchema>

/** `POST /api/yes/:id/decide`. `shownDigest` binds the yes to what the screen showed. */
export const DecideRequestSchema = z.object({
    result: DecisionSchema,
    shownDigest: z.string(),
})
export type DecideRequest = z.output<typeof DecideRequestSchema>
