import {z} from "zod"
import {DateTimeSchema} from "./common"

export const ActivityKindSchema = z
    .enum([
        "session.started",
        "session.ended",
        "gate.requested",
        "gate.decided",
        "check.passed",
        "check.failed",
        "workflow.changed",
        "other",
    ])
    .catch("other")
export type ActivityKind = z.output<typeof ActivityKindSchema>

export const ActorSchema = z.object({
    kind: z.enum(["agent", "person"]),
    name: z.string(),
})
export type Actor = z.output<typeof ActorSchema>

export const ActivityItemSchema = z.object({
    id: z.string(),
    at: DateTimeSchema,
    kind: ActivityKindSchema,
    actor: ActorSchema,
    summary: z.string(),
    repo: z.string().nullish(),
    costUsd: z.number().nullish(),
    askId: z.string().nullish(),
})
export type ActivityItem = z.output<typeof ActivityItemSchema>

/** `GET /api/activity?before=<cursor>`: newest first, keyset pagination. */
export const ActivityPageSchema = z.object({
    items: z.array(ActivityItemSchema),
    nextCursor: z.string().nullish(),
})
export type ActivityPage = z.output<typeof ActivityPageSchema>
