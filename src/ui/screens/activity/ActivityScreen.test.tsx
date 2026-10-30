import {QueryClientProvider} from "@tanstack/react-query"
import {fireEvent, screen} from "@testing-library/react-native"
import {renderRouter} from "expo-router/testing-library"
import type {ReactNode} from "react"
import {type ActivityPage, ActivityPageSchema} from "@/api/contract/activity"
import {ApiError} from "@/api/http/ApiError"
import type {ActivityRepository} from "@/repositories/ActivityRepository"
import {activity, olderActivity} from "@/api/mocks/fixtures"
import {ServicesProvider} from "@/services/AppServices"
import {fakeRepositories, signedInServices} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {ActivityScreen} from "./ActivityScreen"

const first = ActivityPageSchema.parse(activity)
const older = ActivityPageSchema.parse(olderActivity)

async function launch(page: ActivityRepository["page"]): Promise<ReturnType<typeof renderRouter>> {
    const services = await signedInServices({repositories: fakeRepositories({activity: {page}})})
    const client = testQueryClient()
    const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
        <ServicesProvider services={services}>
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
        </ServicesProvider>
    )
    return renderRouter({index: ActivityScreen, "request/[id]": (): React.JSX.Element => <></>}, {initialUrl: "/", wrapper})
}

describe("ActivityScreen", () => {
    it("shows what agents and people did, older pages on demand", async () => {
        const page = jest.fn((cursor?: string) => Promise.resolve(cursor === undefined ? first : older))
        await launch(page)
        expect(await screen.findByText("rev-b approved the merge of PR #6")).toBeOnTheScreen()
        expect(screen.getByText(/Agent claude-code · acme\/acme-api · \$0\.13/)).toBeOnTheScreen()
        await fireEvent.press(screen.getByRole("button", {name: "Show older"}))
        expect(await screen.findByText("Session started")).toBeOnTheScreen()
        expect(page).toHaveBeenLastCalledWith("act_1")
        expect(screen.queryByRole("button", {name: "Show older"})).toBeNull()
    })

    it("opens the request an activity is about", async () => {
        const router = await launch(jest.fn(() => Promise.resolve(first)))
        await fireEvent.press(await screen.findByRole("button", {name: "claude-code asks to merge PR #6"}))
        expect(router.getPathname()).toBe("/request/appr_0001")
    })

    it("lists an activity without repository", async () => {
        const [item] = first.items
        if (item === undefined) throw new Error("no activity in the fixture")
        const bare = {id: item.id, at: item.at, kind: item.kind, summary: item.summary, actor: {kind: "robot", name: "rev-b"}}
        await launch(jest.fn(() => Promise.resolve({items: [bare]})))
        expect(await screen.findByText(/^rev-b · /)).toBeOnTheScreen()
    })

    it("says when nothing happened yet, and offers to try again after a failure", async () => {
        const page = jest
            .fn<Promise<ActivityPage>, Parameters<ActivityRepository["page"]>>()
            .mockRejectedValueOnce(new ApiError({kind: "network"}))
            .mockResolvedValue({items: []})
        await launch(page)
        await fireEvent.press(await screen.findByRole("button", {name: "Try again"}))
        expect(await screen.findByText("No activity yet.")).toBeOnTheScreen()
    })
})
