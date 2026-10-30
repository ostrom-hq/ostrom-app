import {z} from "zod"
import {DateTimeSchema} from "./common"

export const SpendLineSchema = z.object({
    key: z.string(),
    label: z.string(),
    usd: z.number(),
    sessions: z.number().int(),
})
export type SpendLine = z.output<typeof SpendLineSchema>

export const SpendPeriodSchema = z.enum(["week", "month"])
export type SpendPeriod = z.output<typeof SpendPeriodSchema>

/** `GET /api/spending?period=month`: what the agents cost, by agent, repository and person. */
export const SpendReportSchema = z.object({
    period: SpendPeriodSchema,
    from: DateTimeSchema,
    to: DateTimeSchema,
    totalUsd: z.number(),
    budgetUsd: z.number().nullish(),
    byAgent: z.array(SpendLineSchema),
    byRepo: z.array(SpendLineSchema),
    byPerson: z.array(SpendLineSchema),
    daily: z.array(z.object({date: z.iso.date(), usd: z.number()})),
})
export type SpendReport = z.output<typeof SpendReportSchema>
