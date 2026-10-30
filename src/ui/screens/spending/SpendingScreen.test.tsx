// SPDX-License-Identifier: AGPL-3.0-only
import {fireEvent, screen} from "@testing-library/react-native"
import {type SpendPeriod, type SpendReport, SpendReportSchema} from "@/api/contract/spending"
import {ApiError} from "@/api/http/ApiError"
import {spending} from "@/api/mocks/fixtures"
import type {SpendingRepository} from "@/repositories/SpendingRepository"
import {fakeRepositories, signedInServices} from "@/test/fakeServices"
import {renderWithServices} from "@/test/renderWithServices"
import {SpendingScreen} from "./SpendingScreen"

const month = SpendReportSchema.parse(spending)

async function show(report: SpendingRepository["report"]): Promise<void> {
    const services = await signedInServices({repositories: fakeRepositories({spending: {report}})})
    await renderWithServices(<SpendingScreen />, services)
}

describe("SpendingScreen", () => {
    it("shows the month by default: total, budget, and who spent it", async () => {
        await show(jest.fn(() => Promise.resolve(month)))
        expect(await screen.findByText("$182.40")).toBeOnTheScreen()
        expect(screen.getByText("73 % of the $250.00 budget")).toBeOnTheScreen()
        for (const text of ["Claude Code", "$141.20", "acme-api", "dev-a", "Sessions: 212"]) {
            expect(screen.getByText(text)).toBeOnTheScreen()
        }
    })

    it("switches to the week, with no budget and nothing spent by anyone", async () => {
        const report = jest.fn((period: SpendPeriod) =>
            Promise.resolve(
                period === "month" ? month : {period, from: month.from, to: month.to, totalUsd: 0, byAgent: [], byRepo: [], byPerson: [], daily: []},
            ),
        )
        await show(report)
        await screen.findByText("$182.40")
        await fireEvent.press(screen.getByRole("tab", {name: "This week"}))
        expect(await screen.findByText("$0.00")).toBeOnTheScreen()
        expect(screen.getAllByText("Nothing spent.")).toHaveLength(3)
        expect(screen.queryByText(/budget/)).toBeNull()
        expect(report).toHaveBeenLastCalledWith("week")
    })

    it("turns amber near the budget", async () => {
        await show(jest.fn(() => Promise.resolve({...month, totalUsd: 240})))
        expect(await screen.findByText("96 % of the $250.00 budget")).toBeOnTheScreen()
    })

    it("offers to try again after a failure", async () => {
        const report = jest
            .fn<Promise<SpendReport>, [SpendPeriod]>()
            .mockRejectedValueOnce(new ApiError({kind: "server", status: 500}))
            .mockResolvedValue(month)
        await show(report)
        await fireEvent.press(await screen.findByRole("button", {name: "Try again"}))
        expect(await screen.findByText("$182.40")).toBeOnTheScreen()
    })
})
