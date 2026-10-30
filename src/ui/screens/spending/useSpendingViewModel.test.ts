import {SpendReportSchema} from "@/api/contract/spending"
import {spending} from "@/api/mocks/fixtures"
import {breakdownOf, budgetOf} from "./useSpendingViewModel"

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
