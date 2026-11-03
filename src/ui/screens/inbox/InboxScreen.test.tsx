// SPDX-License-Identifier: AGPL-3.0-only
import {QueryClientProvider} from "@tanstack/react-query"
import {act, fireEvent, screen} from "@testing-library/react-native"
import {renderRouter} from "expo-router/testing-library"
import type {ReactNode} from "react"
import type {Approval, ApprovalQueue} from "@/api/contract/approvals"
import {ApiError} from "@/api/http/ApiError"
import {ServicesProvider} from "@/services/AppServices"
import {approvals, fakeRepositories, fakeServices, signedInServices, viewOf} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {InboxScreen} from "./InboxScreen"
import {toRow} from "./useInboxViewModel"

/** The yes queue of `rev-b`: these requests waiting, and these answered lately. */
function queueOf(pending: readonly Approval[], recent: readonly Approval[] = []): ApprovalQueue {
    return {operator: "rev-b", you: {login: "rev-b", via: "app"}, pending: pending.map((a) => viewOf(a)), recent: recent.map((a) => viewOf(a))}
}

async function launch(
    queue: () => Promise<ApprovalQueue>,
    signedIn = true,
    client = testQueryClient(),
    onFirstUsefulScreen?: () => void,
): Promise<ReturnType<typeof renderRouter>> {
    const repositories = fakeRepositories({approvals: {queue, view: jest.fn(), answer: jest.fn()}})
    const signed = signedIn ? await signedInServices({repositories}) : fakeServices({repositories})
    const services = onFirstUsefulScreen === undefined ? signed : {...signed, onFirstUsefulScreen}
    const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
        <ServicesProvider services={services}>
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
        </ServicesProvider>
    )
    return renderRouter(
        {index: InboxScreen, "request/[id]": (): React.JSX.Element => <></>, settings: (): React.JSX.Element => <></>},
        {initialUrl: "/", wrapper},
    )
}

describe("InboxScreen", () => {
    it("lists what waits for my answer, and only opens a request: no answer from the list", async () => {
        // A request answered elsewhere since the list was read keeps its row, without the garnet edge.
        const bare: Approval = {id: "appr_bare", title: "Bare request", requestedFrom: "rev-b", channel: "app", status: "pending", shownDigest: "d"}
        const decided = {...approvals.decided, askedBy: ["dev-c"]}
        const router = await launch(() => Promise.resolve(queueOf([approvals.pending, approvals.self, decided, bare])))
        expect(await screen.findByText("Merge PR #6 into main")).toBeOnTheScreen()
        expect(screen.getByText("acme/acme-api · dev-a")).toBeOnTheScreen()
        expect(screen.getByText("Your request")).toBeOnTheScreen()
        expect(screen.queryByRole("button", {name: "Approve"})).toBeNull()
        await fireEvent.press(screen.getByRole("button", {name: "Merge PR #6 into main"}))
        expect(router.getPathname()).toBe("/request/appr_0001")
    })

    it("tells the services when the list, the first useful screen, is laid out", async () => {
        const onFirstUsefulScreen = jest.fn()
        await launch(() => Promise.resolve(queueOf([approvals.pending])), true, testQueryClient(), onFirstUsefulScreen)
        const list = await screen.findByTestId("inbox-list")
        await fireEvent(list, "layout", {nativeEvent: {layout: {x: 0, y: 0, width: 400, height: 800}}})
        expect(onFirstUsefulScreen).toHaveBeenCalled()
    })

    it("calls nothing mine once the session is gone", async () => {
        const queue = jest.fn<Promise<ApprovalQueue>, []>(() => Promise.resolve(queueOf([approvals.self])))
        await launch(queue, false)
        expect(await screen.findByRole("button", {name: "Try again"})).toBeOnTheScreen()
        expect(queue).not.toHaveBeenCalled()
    })

    it("says when nothing waits", async () => {
        await launch(() => Promise.resolve(queueOf([])))
        expect(await screen.findByText("Nothing waits for your yes right now.")).toBeOnTheScreen()
        expect(screen.queryByText("Answered lately")).toBeNull()
    })

    it("shows what was answered lately after what waits, and opens it", async () => {
        const router = await launch(() => Promise.resolve(queueOf([], [approvals.decided])))
        expect(await screen.findByText("Nothing waits for your yes right now.")).toBeOnTheScreen()
        expect(screen.getByRole("header", {name: "Answered lately"})).toBeOnTheScreen()
        expect(screen.getByText("Approved")).toBeOnTheScreen()
        await fireEvent.press(screen.getByRole("button", {name: "Push to main on acme-api"}))
        expect(router.getPathname()).toBe("/request/appr_0000")
    })

    it("keeps the last list when a refresh fails, and says it may be out of date", async () => {
        const queue = jest
            .fn<Promise<ApprovalQueue>, []>()
            .mockResolvedValueOnce(queueOf([approvals.pending]))
            .mockRejectedValue(new ApiError({kind: "network"}))
        const client = testQueryClient()
        await launch(queue, true, client)
        expect(await screen.findByText("Merge PR #6 into main")).toBeOnTheScreen()
        await act(async () => {
            await client.invalidateQueries()
        })
        expect(await screen.findByRole("alert")).toHaveTextContent(/could not be refreshed/)
        expect(screen.getByText("Merge PR #6 into main")).toBeOnTheScreen()
    })

    it("says why a refresh failed when the server wants the organization named", async () => {
        const queue = jest
            .fn<Promise<ApprovalQueue>, []>()
            .mockResolvedValueOnce(queueOf([approvals.pending]))
            .mockRejectedValue(new ApiError({kind: "refused", status: 409, type: "org_required", message: ""}))
        const client = testQueryClient()
        const router = await launch(queue, true, client)
        expect(await screen.findByText("Merge PR #6 into main")).toBeOnTheScreen()
        await act(async () => {
            await client.invalidateQueries()
        })
        // One alert, read out once by screen readers: the list may be out of date, and why.
        const alert = await screen.findByRole("alert")
        expect(alert).toHaveTextContent(/could not be refreshed[\s\S]*several organizations/)
        expect(screen.getByText("Merge PR #6 into main")).toBeOnTheScreen()
        await fireEvent.press(screen.getByRole("button", {name: "Choose the organization"}))
        expect(router.getPathname()).toBe("/settings")
    })

    it("says clearly when the server wants the organization named, and leads to the choice in Settings", async () => {
        const router = await launch(() => Promise.reject(new ApiError({kind: "refused", status: 409, type: "org_required", message: ""})))
        expect(
            await screen.findByText(
                "You are a member of several organizations, and Ostrom needs to know which one this is for: choose it in Settings.",
            ),
        ).toBeOnTheScreen()
        expect(screen.queryByText("Your requests could not be loaded.")).toBeNull()
        await fireEvent.press(screen.getByRole("button", {name: "Choose the organization"}))
        expect(router.getPathname()).toBe("/settings")
    })

    it("offers no choice of organization on a plain failure", async () => {
        await launch(() => Promise.reject(new ApiError({kind: "network"})))
        expect(await screen.findByText("Your requests could not be loaded.")).toBeOnTheScreen()
        expect(screen.queryByRole("button", {name: "Choose the organization"})).toBeNull()
    })

    it("offers to try again when the requests cannot be loaded", async () => {
        const queue = jest
            .fn<Promise<ApprovalQueue>, []>()
            .mockRejectedValueOnce(new ApiError({kind: "network"}))
            .mockResolvedValue(queueOf([approvals.pending]))
        await launch(queue)
        await fireEvent.press(await screen.findByRole("button", {name: "Try again"}))
        expect(await screen.findByText("Merge PR #6 into main")).toBeOnTheScreen()
    })
})

describe("toRow", () => {
    it("keeps the second line empty when the server says neither where nor who", () => {
        const bare: Approval = {id: "appr_1", title: "Bare", requestedFrom: "rev-b", channel: "app", status: "pending", shownDigest: "d"}
        expect(toRow(viewOf(bare))).toEqual({id: "appr_1", title: "Bare", status: "pending", detail: "", yours: false})
    })

    it("names the first asker, and is mine when the server says so", () => {
        const approval: Approval = {...approvals.pending, repo: "", askedBy: ["dev-c", "rev-b"]}
        expect(toRow(viewOf(approval))).toMatchObject({detail: "dev-c", yours: true})
        expect(toRow(viewOf(approval, {self: false})).yours).toBe(false)
    })
})
