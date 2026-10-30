// SPDX-License-Identifier: AGPL-3.0-only
import {useState} from "react"
import type {SpendLine, SpendPeriod, SpendReport} from "@/api/contract/spending"
import {useSpending} from "@/query/spending"
import {isPresent} from "@/ui/format/presence"

/** Above this share of the budget the total turns amber: budget is the only use of amber (brand rule). */
export const BUDGET_WARNING_RATIO = 0.8

export interface Budget {
    readonly usd: number
    /** Spent over budget, from 0; above 1 when the budget is passed. */
    readonly ratio: number
    readonly warning: boolean
}

export interface Breakdown {
    readonly key: "byAgent" | "byRepo" | "byPerson"
    readonly lines: readonly (SpendLine & {readonly share: number})[]
}

export type SpendingState =
    | {readonly kind: "loading"}
    | {readonly kind: "error"}
    | {readonly kind: "ready"; readonly totalUsd: number; readonly budget: Budget | undefined; readonly breakdowns: readonly Breakdown[]}

export interface SpendingViewModel {
    readonly period: SpendPeriod
    readonly setPeriod: (period: SpendPeriod) => void
    readonly state: SpendingState
    readonly retry: () => void
}

export function budgetOf(report: SpendReport): Budget | undefined {
    const {budgetUsd} = report
    if (!isPresent(budgetUsd) || budgetUsd <= 0) return undefined
    const ratio = report.totalUsd / budgetUsd
    return {usd: budgetUsd, ratio, warning: ratio >= BUDGET_WARNING_RATIO}
}

/** Each line with its share of the biggest one, for the bars; biggest first. */
export function breakdownOf(key: Breakdown["key"], lines: readonly SpendLine[]): Breakdown {
    const sorted = [...lines].sort((a, b) => b.usd - a.usd)
    const top = sorted[0]?.usd ?? 0
    return {key, lines: sorted.map((line) => ({...line, share: top > 0 ? line.usd / top : 0}))}
}

export function useSpendingViewModel(): SpendingViewModel {
    const [period, setPeriod] = useState<SpendPeriod>("month")
    const {data, isError, refetch} = useSpending(period)
    const state: SpendingState =
        data !== undefined
            ? {
                  kind: "ready",
                  totalUsd: data.totalUsd,
                  budget: budgetOf(data),
                  breakdowns: [breakdownOf("byAgent", data.byAgent), breakdownOf("byRepo", data.byRepo), breakdownOf("byPerson", data.byPerson)],
              }
            : isError
              ? {kind: "error"}
              : {kind: "loading"}
    return {
        period,
        setPeriod,
        state,
        retry: () => {
            void refetch()
        },
    }
}
