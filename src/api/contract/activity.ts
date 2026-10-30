import type {z} from "zod"
import {zListActivityResponse} from "@/api/gen/zod.gen"

/** `GET /api/v1/activity?before=<cursor>`: newest first, `next` is the cursor of the older page. */
export const ActivityPageSchema = zListActivityResponse
export type ActivityPage = z.output<typeof zListActivityResponse>
export type ActivityItem = ActivityPage["items"][number]

const KINDS = [
    "session.started",
    "session.ended",
    "gate.requested",
    "gate.decided",
    "check.passed",
    "check.failed",
    "workflow.changed",
] as const

/** The kinds this app knows; the contract says a client keeps any other as unknown (`other`). */
export type ActivityKind = (typeof KINDS)[number] | "other"

function isKnown(kind: string): kind is (typeof KINDS)[number] {
    return KINDS.some((known) => known === kind)
}

export function activityKind(kind: string): ActivityKind {
    return isKnown(kind) ? kind : "other"
}
