// SPDX-License-Identifier: AGPL-3.0-only
import {QueryClientProvider} from "@tanstack/react-query"
import {fireEvent, screen, within} from "@testing-library/react-native"
import {renderRouter} from "expo-router/testing-library"
import type {ReactNode} from "react"
import type {Ask, YesQueue} from "@/api/contract/yes"
import {ApiError} from "@/api/http/ApiError"
import {ServicesProvider} from "@/services/AppServices"
import {asks, fakeRepositories, signedInServices} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {InboxScreen} from "./InboxScreen"
import {toRow} from "./useInboxViewModel"

async function launch(queue: () => Promise<YesQueue>): Promise<ReturnType<typeof renderRouter>> {
    const services = await signedInServices({
        repositories: fakeRepositories({approvals: {queue, ask: () => Promise.resolve(asks.pending), decide: jest.fn()}}),
    })
    const client = testQueryClient()
    const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
        <ServicesProvider services={services}>
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
        </ServicesProvider>
    )
    return renderRouter(
        {index: InboxScreen, "ask/[id]": (): React.JSX.Element => <></>},
        {initialUrl: "/", wrapper},
    )
}

describe("InboxScreen", () => {
    it("lists what waits for me first, then what was decided, and opens a request", async () => {
        const router = await launch(() =>
            Promise.resolve({
                operator: "rev-b",
                pending: [asks.pending, asks.self],
                recent: [asks.decided, {...asks.decided, id: "appr_bare", title: "Bare request", who: {}, what: {}, askedBy: []}],
            }),
        )
        expect(await screen.findByText("Merge PR #6 into main")).toBeOnTheScreen()
        // The pending request and the decided one share their repository and asker: the line is read inside its row.
        expect(within(screen.getByRole("button", {name: "Merge PR #6 into main"})).getByText("acme/acme-api · dev-a")).toBeOnTheScreen()
        expect(screen.getByText("Your request")).toBeOnTheScreen()
        expect(screen.getByText("Recently decided")).toBeOnTheScreen()
        expect(screen.getByText("Push to main on acme-api")).toBeOnTheScreen()
        expect(screen.getByText("Bare request")).toBeOnTheScreen()
        await fireEvent.press(screen.getByRole("button", {name: "Merge PR #6 into main"}))
        expect(router.getPathname()).toBe("/ask/appr_0001")
    })

    it("says when nothing waits", async () => {
        await launch(() => Promise.resolve({operator: "rev-b", pending: [], recent: []}))
        expect(await screen.findByText("Nothing waits for your yes right now.")).toBeOnTheScreen()
        expect(screen.queryByText("Recently decided")).toBeNull()
    })

    it("offers to try again when the requests cannot be loaded", async () => {
        const queue = jest
            .fn<Promise<YesQueue>, []>()
            .mockRejectedValueOnce(new ApiError({kind: "network"}))
            .mockResolvedValue({operator: "rev-b", pending: [asks.pending], recent: []})
        await launch(queue)
        await fireEvent.press(await screen.findByRole("button", {name: "Try again"}))
        expect(await screen.findByText("Merge PR #6 into main")).toBeOnTheScreen()
    })
})

describe("toRow", () => {
    it("keeps the second line empty when the server says neither where nor who", () => {
        const bare: Ask = {...asks.pending, who: {}, what: {}, askedBy: [], you: null}
        expect(toRow(bare)).toEqual({id: "appr_0001", title: "Merge PR #6 into main", status: "pending", detail: "", yours: false})
    })

    it("falls back on the session repository and the first asker", () => {
        const ask: Ask = {...asks.pending, who: {}, what: {sessionRepo: "acme/tools"}, askedBy: ["dev-c"]}
        expect(toRow(ask).detail).toBe("acme/tools · dev-c")
    })
})
