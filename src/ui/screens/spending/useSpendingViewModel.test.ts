// SPDX-License-Identifier: AGPL-3.0-only
import {SpendReportSchema} from "@/api/contract/spending"
import {spending} from "@/api/mocks/fixtures"
import {breakdownOf, budgetOf, exceededOf} from "./useSpendingViewModel"

const report = SpendReportSchema.parse(spending)

describe("budgetOf", () => {
    it("measures the spending against the budget, amber from 80 %", () => {
        expect(budgetOf(report)).toEqual({usd: 250, ratio: 182.4 / 250, warning: false})
        expect(budgetOf({...report, totalUsd: 200})?.warning).toBe(true)
        expect(budgetOf({...report, totalUsd: 300})?.ratio).toBeGreaterThan(1)
    })

    it("has no budget when none is set", () => {
        expect(budgetOf(SpendReportSchema.parse({...spending, budgetUsd: undefined}))).toBeUndefined()
        expect(budgetOf({...report, budgetUsd: 0})).toBeUndefined()
    })
})

describe("breakdownOf", () => {
    it("puts the biggest first, with each share of the biggest", () => {
        const {lines} = breakdownOf("byAgent", [...report.byAgent].reverse())
        expect(lines.map((line) => line.key)).toEqual(["claude-code", "codex"])
        expect(lines[0]?.share).toBe(1)
        expect(lines[1]?.share).toBeCloseTo(41.2 / 141.2)
    })

    it("draws no bar when nothing was spent", () => {
        expect(breakdownOf("byRepo", [{key: "a", label: "a", usd: 0, sessions: 0}]).lines[0]?.share).toBe(0)
        expect(breakdownOf("byPerson", []).lines).toEqual([])
    })
})

describe("exceededOf", () => {
    const since = "2026-11-03T09:00:00.000Z"
    const over = SpendReportSchema.parse({
        ...spending,
        byPerson: [
            {key: "m-rev-b", label: "rev-b", usd: 6, sessions: 3},
            {key: "m-dev-a", label: "dev-a", usd: 120, sessions: 9},
        ],
        exceeded: [
            {scope: "member", subject: "m-rev-b", period: "day", usd: 5, hardStop: false, since},
            {scope: "member", subject: "m-dev-a", period: "month", usd: 100, hardStop: true, since},
            {scope: "member", subject: "m-gone", period: "month", usd: 50, hardStop: false, since},
            {scope: "organization", period: "month", usd: 250, hardStop: true, since},
            {scope: "org", period: "day", usd: 20, hardStop: false, since},
        ],
    })

    it("names each budget passed: mine, another person's by their login, the organization's", () => {
        expect(exceededOf(over, "rev-b")).toEqual([
            {who: {kind: "me"}, period: "day", usd: 5, stops: false},
            {who: {kind: "member", name: "dev-a"}, period: "month", usd: 100, stops: true},
            {who: {kind: "member", name: undefined}, period: "month", usd: 50, stops: false},
            {who: {kind: "organization"}, period: "month", usd: 250, stops: true},
            {who: {kind: "organization"}, period: "day", usd: 20, stops: false},
        ])
    })

    it("is mine only when the login is mine", () => {
        expect(exceededOf(over, "dev-a")[0]).toEqual({who: {kind: "member", name: "rev-b"}, period: "day", usd: 5, stops: false})
    })

    it("has nothing passed when the server lists none, or does not say", () => {
        expect(exceededOf({...over, exceeded: []}, "rev-b")).toEqual([])
        expect(exceededOf(report, "rev-b")).toEqual([])
    })
})
