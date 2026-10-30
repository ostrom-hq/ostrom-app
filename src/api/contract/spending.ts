import type {z} from "zod"
import {zSpending} from "@/api/gen/zod.gen"

/** The periods the app asks for (`GET /api/v1/spending?period=`). */
export type SpendPeriod = "week" | "month"

/** What the agents cost, by agent, repository and person. */
export const SpendReportSchema = zSpending
export type SpendReport = z.output<typeof zSpending>
export type SpendLine = SpendReport["byAgent"][number]
