import {QueryClientProvider} from "@tanstack/react-query"
import {act, fireEvent, screen} from "@testing-library/react-native"
import {renderRouter} from "expo-router/testing-library"
import type {ReactNode} from "react"
import type {Approval, ApprovalPage} from "@/api/contract/approvals"
import {ApiError} from "@/api/http/ApiError"
import {ServicesProvider} from "@/services/AppServices"
import {approvals, fakeRepositories, fakeServices, signedInServices} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {InboxScreen} from "./InboxScreen"
import {toRow} from "./useInboxViewModel"

async function launch(
    waiting: () => Promise<ApprovalPage>,
    signedIn = true,
    client = testQueryClient(),
): Promise<ReturnType<typeof renderRouter>> {
    const repositories = fakeRepositories({approvals: {waiting, approval: () => Promise.resolve(approvals.pending), answer: jest.fn()}})
    const services = signedIn ? await signedInServices({repositories}) : fakeServices({repositories})
    const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
        <ServicesProvider services={services}>
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
        </ServicesProvider>
    )
    return renderRouter({index: InboxScreen, "request/[id]": (): React.JSX.Element => <></>}, {initialUrl: "/", wrapper})
}

describe("InboxScreen", () => {
    it("lists what waits for my answer, and only opens a request: no answer from the list", async () => {
        // A request answered elsewhere since the list was read keeps its row, without the garnet edge.
        const bare: Approval = {id: "appr_bare", title: "Bare request", requestedFrom: "rev-b", channel: "app", status: "pending", shownDigest: "d"}
        const decided = {...approvals.decided, askedBy: ["dev-c"]}
        const router = await launch(() => Promise.resolve({items: [approvals.pending, approvals.self, decided, bare]}))
        expect(await screen.findByText("Merge PR #6 into main")).toBeOnTheScreen()
        expect(screen.getByText("acme/acme-api · dev-a")).toBeOnTheScreen()
        expect(screen.getByText("Your request")).toBeOnTheScreen()
        expect(screen.queryByRole("button", {name: "Approve"})).toBeNull()
        await fireEvent.press(screen.getByRole("button", {name: "Merge PR #6 into main"}))
        expect(router.getPathname()).toBe("/request/appr_0001")
    })

    it("calls nothing mine once the session is gone", async () => {
        await launch(() => Promise.resolve({items: [approvals.self]}), false)
        expect(await screen.findByText("Push to main on acme-web")).toBeOnTheScreen()
        expect(screen.queryByText("Your request")).toBeNull()
    })

    it("says when nothing waits", async () => {
        await launch(() => Promise.resolve({items: []}))
        expect(await screen.findByText("Nothing waits for your yes right now.")).toBeOnTheScreen()
    })

    it("keeps the last list when a refresh fails, and says it may be out of date", async () => {
        const waiting = jest
            .fn<Promise<ApprovalPage>, []>()
            .mockResolvedValueOnce({items: [approvals.pending]})
            .mockRejectedValue(new ApiError({kind: "network"}))
        const client = testQueryClient()
        await launch(waiting, true, client)
        expect(await screen.findByText("Merge PR #6 into main")).toBeOnTheScreen()
        await act(async () => {
            await client.invalidateQueries()
        })
        expect(await screen.findByRole("alert")).toHaveTextContent(/could not be refreshed/)
        expect(screen.getByText("Merge PR #6 into main")).toBeOnTheScreen()
    })

    it("offers to try again when the requests cannot be loaded", async () => {
        const waiting = jest
            .fn<Promise<ApprovalPage>, []>()
            .mockRejectedValueOnce(new ApiError({kind: "network"}))
            .mockResolvedValue({items: [approvals.pending]})
        await launch(waiting)
        await fireEvent.press(await screen.findByRole("button", {name: "Try again"}))
        expect(await screen.findByText("Merge PR #6 into main")).toBeOnTheScreen()
    })
})

describe("toRow", () => {
    it("keeps the second line empty when the server says neither where nor who", () => {
        const bare: Approval = {id: "appr_1", title: "Bare", requestedFrom: "rev-b", channel: "app", status: "pending", shownDigest: "d"}
        expect(toRow(bare, "rev-b")).toEqual({id: "appr_1", title: "Bare", status: "pending", detail: "", yours: false})
    })

    it("names the first asker, and is never mine without a session", () => {
        const approval: Approval = {...approvals.pending, repo: "", askedBy: ["dev-c", "rev-b"]}
        expect(toRow(approval, "rev-b")).toMatchObject({detail: "dev-c", yours: true})
        expect(toRow(approval, undefined).yours).toBe(false)
    })
})
