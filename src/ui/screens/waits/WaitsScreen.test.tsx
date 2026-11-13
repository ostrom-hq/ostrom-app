// SPDX-License-Identifier: AGPL-3.0-only
import {QueryClientProvider} from "@tanstack/react-query"
import {act, fireEvent, screen, waitFor} from "@testing-library/react-native"
import i18next from "i18next"
import {renderRouter} from "expo-router/testing-library"
import type {ReactNode} from "react"
import {type Wait, WaitSchema, type WaitsPage, type WaitValues} from "@/api/contract/waits"
import {ApiError} from "@/api/http/ApiError"
import {answeredWait, cancelledWait, confirmWait, openWait} from "@/api/mocks/fixtures"
import type {WaitsRepository} from "@/repositories/WaitsRepository"
import {type AppServices, ServicesProvider} from "@/services/AppServices"
import type {UnlockOutcome} from "@/services/unlock/PhoneUnlock"
import {fakeRepositories, signedInServices} from "@/test/fakeServices"
import {testQueryClient} from "@/test/renderWithServices"
import {WaitsScreen} from "./WaitsScreen"

const OPEN = "0b1f6c1e-0000-4000-8000-000000000003"
const CONFIRM = "0b1f6c1e-0000-4000-8000-000000000002"

const open = WaitSchema.parse(openWait)
const confirm = WaitSchema.parse(confirmWait)
const answered = WaitSchema.parse(answeredWait)
const cancelled = WaitSchema.parse(cancelledWait)

/** A wait of the fixtures with something else in its question. */
function wait(id: string, question: Partial<Wait["question"]>, extra: Partial<Wait> = {}): Wait {
    return {...confirm, id, question: {...confirm.question, ...question}, ...extra}
}

interface Harness {
    readonly list: jest.Mock<Promise<WaitsPage>, Parameters<WaitsRepository["list"]>>
    readonly answer: jest.Mock<Promise<Wait>, Parameters<WaitsRepository["answer"]>>
    readonly unlock: jest.Mock<Promise<UnlockOutcome>, [string]>
    readonly openUrl: jest.Mock<Promise<void>, [string]>
    readonly router: Awaited<ReturnType<typeof renderRouter>>
    readonly client: ReturnType<typeof testQueryClient>
}

interface Options {
    readonly open?: readonly Wait[]
    readonly list?: WaitsRepository["list"]
    readonly answer?: WaitsRepository["answer"]
    readonly outcome?: UnlockOutcome | Error
    readonly openUrl?: (url: string) => Promise<void>
}

/** A server whose lists follow the fixtures, and a phone whose unlock answers `outcome`. */
async function launch({open: openWaits = [open, confirm], list, answer, outcome = "confirmed", openUrl}: Options = {}): Promise<Harness> {
    const byFilter = {open: openWaits, answered: [answered], cancelled: [cancelled]}
    const listMock = jest.fn<Promise<WaitsPage>, Parameters<WaitsRepository["list"]>>(
        list ?? ((filter) => Promise.resolve({items: [...byFilter[filter]]})),
    )
    const answerMock = jest.fn<Promise<Wait>, Parameters<WaitsRepository["answer"]>>(
        answer ?? ((id, action, values: WaitValues) => Promise.resolve({...open, id, status: "resolved", answer: {action, values, author: "rev-b", answeredAt: "2026-11-13T10:00:00Z"}})),
    )
    const unlock = jest.fn((_prompt: string) => (outcome instanceof Error ? Promise.reject(outcome) : Promise.resolve(outcome)))
    const openUrlMock = jest.fn(openUrl ?? (() => Promise.resolve()))
    const services: AppServices = {
        ...(await signedInServices({repositories: fakeRepositories({waits: {list: listMock, answer: answerMock}})})),
        unlock: {keep: jest.fn(), unlock, forget: jest.fn()},
        openUrl: openUrlMock,
    }
    const client = testQueryClient()
    const wrapper = ({children}: {children: ReactNode}): React.JSX.Element => (
        <ServicesProvider services={services}>
            <QueryClientProvider client={client}>{children}</QueryClientProvider>
        </ServicesProvider>
    )
    const router = await renderRouter({index: WaitsScreen, settings: (): React.JSX.Element => <></>}, {initialUrl: "/", wrapper})
    return {list: listMock, answer: answerMock, unlock, openUrl: openUrlMock, router, client}
}

const refused = (status: number, type: string): ApiError => new ApiError({kind: "refused", status, type, message: ""})

afterEach(async () => {
    await i18next.changeLanguage("en")
})

describe("WaitsScreen", () => {
    it("lists what is asked of me, newest first, with the count of open waits", async () => {
        const {list} = await launch()
        expect(await screen.findByText("Which database for the cache?")).toBeOnTheScreen()
        expect(screen.getByText("Ship the migration?")).toBeOnTheScreen()
        expect(screen.getByText("The cache needs a store before the release.")).toBeOnTheScreen()
        expect(screen.getByText(/Asked .* by claude-code/)).toBeOnTheScreen()
        expect(screen.getByLabelText("2 waiting")).toBeOnTheScreen()
        expect(screen.getByText("2")).toBeOnTheScreen()
        expect(list).toHaveBeenCalledWith("open", {cursor: undefined})
        expect(list).toHaveBeenCalledWith("open", {limit: 100})
    })

    it("shows the answered waits with what was chosen, and the withdrawn ones", async () => {
        await launch()
        await fireEvent.press(await screen.findByTestId("waits-filter-answered"))
        expect(await screen.findByText("Use semantic versions?")).toBeOnTheScreen()
        expect(screen.getByText(/Send · Answered by rev-b/)).toBeOnTheScreen()
        expect(screen.queryByRole("button", {name: "Send"})).toBeNull()
        await fireEvent.press(screen.getByTestId("waits-filter-cancelled"))
        expect(await screen.findByText("Pick a release date")).toBeOnTheScreen()
        expect(screen.getByText(/^Withdrawn · /)).toBeOnTheScreen()
        expect(screen.queryByTestId("wait-0b1f6c1e-0000-4000-8000-000000000000-date")).toBeNull()
    })

    it("says when nothing is asked, answered or withdrawn", async () => {
        await launch({list: () => Promise.resolve({items: []})})
        expect(await screen.findByText("Nothing is asked of you right now.")).toBeOnTheScreen()
        expect(screen.queryByTestId("waits-badge")).toBeNull()
        await fireEvent.press(screen.getByTestId("waits-filter-answered"))
        expect(await screen.findByText("You have not answered any question yet.")).toBeOnTheScreen()
        await fireEvent.press(screen.getByTestId("waits-filter-cancelled"))
        expect(await screen.findByText("No question was withdrawn.")).toBeOnTheScreen()
    })

    it("answers in place with the fields filled, after the phone's unlock", async () => {
        const {answer, unlock, list} = await launch()
        await fireEvent.press(await screen.findByTestId(`wait-${OPEN}-store-redis`))
        await fireEvent.changeText(screen.getByTestId(`wait-${OPEN}-ttl`), "90")
        await fireEvent.changeText(screen.getByTestId(`wait-${OPEN}-reason`), "fast")
        await fireEvent.press(screen.getByRole("button", {name: "Choose"}))
        await waitFor(() => {
            expect(answer).toHaveBeenCalledWith(OPEN, "choose", {store: "redis", ttl: 90, reason: "fast"})
        })
        expect(unlock).toHaveBeenCalledWith("Answer “Which database for the cache?”", expect.anything(), {always: false})
        // The lists and the badge are read again.
        await waitFor(() => {
            expect(list.mock.calls.filter(([filter]) => filter === "open").length).toBeGreaterThan(2)
        })
    })

    it("checks the required field before anything leaves, and the action's own", async () => {
        const {answer, unlock} = await launch()
        await fireEvent.press(await screen.findByRole("button", {name: "Choose"}))
        expect(await screen.findByText("Fill this in.")).toBeOnTheScreen()
        expect(answer).not.toHaveBeenCalled()
        expect(unlock).not.toHaveBeenCalled()
        // An action that needs nothing goes without the field.
        await fireEvent.press(screen.getByRole("button", {name: "Not now"}))
        await waitFor(() => {
            expect(answer).toHaveBeenCalledWith(OPEN, "defer", {ttl: 60})
        })
    })

    it("fills a choice with another answer, up to 500 characters, and one option at a time", async () => {
        const {answer} = await launch()
        await fireEvent.press(await screen.findByTestId(`wait-${OPEN}-store-redis`))
        expect(screen.getByTestId(`wait-${OPEN}-store-redis`)).toBeChecked()
        await fireEvent.press(screen.getByTestId(`wait-${OPEN}-store-memcached`))
        expect(screen.getByTestId(`wait-${OPEN}-store-redis`)).not.toBeChecked()
        await fireEvent.press(screen.getByTestId(`wait-${OPEN}-store-memcached`))
        expect(screen.getByTestId(`wait-${OPEN}-store-memcached`)).not.toBeChecked()
        await fireEvent.press(screen.getByTestId(`wait-${OPEN}-store-redis`))
        await fireEvent.changeText(screen.getByTestId(`wait-${OPEN}-store-other`), "x".repeat(501))
        expect(screen.getByTestId(`wait-${OPEN}-store-redis`)).not.toBeChecked()
        await fireEvent.press(screen.getByRole("button", {name: "Choose"}))
        expect(await screen.findByText("At most 500 characters.")).toBeOnTheScreen()
        await fireEvent.changeText(screen.getByTestId(`wait-${OPEN}-store-other`), "valkey")
        await fireEvent.press(screen.getByRole("button", {name: "Choose"}))
        await waitFor(() => {
            expect(answer).toHaveBeenCalledWith(OPEN, "choose", {store: "valkey", ttl: 60})
        })
    })

    it("fills every other kind of field: multiple choice, confirm, number, date and info", async () => {
        const id = "0b1f6c1e-0000-4000-8000-0000000000aa"
        const everything = wait(id, {
            title: "Everything",
            fields: [
                {id: "info", type: "info", label: "Read this", help: "Heads up"},
                {id: "tags", type: "choice", label: "Tags", options: ["a", "b"], multiple: true, allowOther: true, required: true, help: "Pick some"},
                {id: "sure", type: "confirm", label: "Sure?", help: "Really"},
                {id: "n", type: "number", label: "Count", required: true, help: "A number"},
                {id: "d", type: "date", label: "Day", required: true},
                {id: "t", type: "text", label: "Note", maxLength: 5, help: "Short"},
                {id: "f", type: "file", label: "Attachment"},
            ],
            actions: [{id: "send", label: "Send", style: "primary"}],
            refs: [],
        })
        const {answer} = await launch({open: [everything]})
        expect(await screen.findByText("Heads up")).toBeOnTheScreen()
        expect(screen.getByText("Read this")).toBeOnTheScreen()
        expect(screen.queryByText("Attachment")).toBeNull()
        await fireEvent.press(screen.getByRole("button", {name: "Send"}))
        expect(await screen.findAllByText("Fill this in.")).toHaveLength(3)
        await fireEvent.press(screen.getByTestId(`wait-${id}-tags-a`))
        await fireEvent.press(screen.getByTestId(`wait-${id}-tags-b`))
        await fireEvent.press(screen.getByTestId(`wait-${id}-tags-a`))
        expect(screen.getByTestId(`wait-${id}-tags-a`)).not.toBeChecked()
        await fireEvent.changeText(screen.getByTestId(`wait-${id}-tags-other`), "c")
        await fireEvent(screen.getByTestId(`wait-${id}-sure`), "valueChange", true)
        await fireEvent.changeText(screen.getByTestId(`wait-${id}-n`), "oops")
        await fireEvent.changeText(screen.getByTestId(`wait-${id}-d`), "2026-02-30")
        await fireEvent.changeText(screen.getByTestId(`wait-${id}-t`), "too long")
        await fireEvent.press(screen.getByRole("button", {name: "Send"}))
        expect(await screen.findByText("Enter a number.")).toBeOnTheScreen()
        expect(screen.getByText("Enter a real date as YYYY-MM-DD.")).toBeOnTheScreen()
        expect(screen.getByText("At most 5 characters.")).toBeOnTheScreen()
        expect(answer).not.toHaveBeenCalled()
        await fireEvent.changeText(screen.getByTestId(`wait-${id}-n`), "3.5")
        await fireEvent.changeText(screen.getByTestId(`wait-${id}-d`), "2026-11-13")
        await fireEvent.changeText(screen.getByTestId(`wait-${id}-t`), "short")
        await fireEvent.press(screen.getByRole("button", {name: "Send"}))
        await waitFor(() => {
            expect(answer).toHaveBeenCalledWith(id, "send", {tags: ["b", "c"], sure: true, n: 3.5, d: "2026-11-13", t: "short"})
        })
    })

    it("says that a multiple choice without free answer shows its error under the options", async () => {
        const id = "0b1f6c1e-0000-4000-8000-0000000000ab"
        await launch({open: [wait(id, {fields: [{id: "c", type: "choice", label: "Pick", options: ["a"], required: true}], actions: [{id: "ok", label: "OK"}]})]})
        await fireEvent.press(await screen.findByRole("button", {name: "OK"}))
        expect(await screen.findByText("Fill this in.")).toBeOnTheScreen()
    })

    it("draws the button of each style, and gives a wait without action the one it has", async () => {
        const id = "0b1f6c1e-0000-4000-8000-0000000000ac"
        const {answer} = await launch({open: [wait(id, {actions: [], fields: []}), confirm]})
        expect(await screen.findByRole("button", {name: "Ship it"})).toBeOnTheScreen()
        expect(screen.getByRole("button", {name: "Hold"})).toBeOnTheScreen()
        await fireEvent.press(screen.getByTestId(`wait-${id}-action-done`))
        await waitFor(() => {
            expect(answer).toHaveBeenCalledWith(id, "done", {})
        })
    })

    it("sends one answer at a time and shows the busy action", async () => {
        let release: (wait: Wait) => void = () => undefined
        const {answer} = await launch({
            answer: () =>
                new Promise<Wait>((resolve) => {
                    release = resolve
                }),
        })
        await fireEvent.press(await screen.findByTestId(`wait-${CONFIRM}-action-yes`))
        await waitFor(() => {
            expect(answer).toHaveBeenCalledTimes(1)
        })
        await fireEvent.press(screen.getByTestId(`wait-${CONFIRM}-action-no`))
        await fireEvent.press(screen.getByTestId(`wait-${CONFIRM}-action-yes`))
        expect(answer).toHaveBeenCalledTimes(1)
        expect(screen.getByTestId(`wait-${CONFIRM}-action-yes`)).toBeDisabled()
        release(confirm)
        await waitFor(() => {
            expect(screen.getByTestId(`wait-${CONFIRM}-action-no`)).toBeEnabled()
        })
    })

    it("says a question was already answered or withdrawn, and reads the list again", async () => {
        let reads = 0
        await launch({
            list: (filter) => Promise.resolve({items: filter === "open" && ++reads > 2 ? [] : [confirm]}),
            answer: () => Promise.reject(refused(409, "wait_closed")),
        })
        await fireEvent.press(await screen.findByTestId(`wait-${CONFIRM}-action-yes`))
        expect(await screen.findByText(/already answered or withdrawn/)).toBeOnTheScreen()
        await waitFor(() => {
            expect(screen.queryByText("Ship the migration?")).toBeNull()
        })
        await fireEvent.press(screen.getByTestId("waits-closed"))
        expect(screen.queryByTestId("waits-closed")).toBeNull()
    })

    it("drops the closed notice when another list is chosen", async () => {
        await launch({answer: () => Promise.reject(refused(409, "wait_closed"))})
        await fireEvent.press(await screen.findByTestId(`wait-${CONFIRM}-action-yes`))
        await screen.findByTestId("waits-closed")
        await fireEvent.press(screen.getByTestId("waits-filter-answered"))
        expect(screen.queryByTestId("waits-closed")).toBeNull()
    })

    it.each([
        [refused(403, "forbidden"), /may not answer this question/],
        [refused(400, "invalid_answer"), /did not accept this answer/],
        [refused(409, "org_required"), /member of several organizations/],
        [new ApiError({kind: "network"}), /could not be reached/],
        [new ApiError({kind: "server", status: 500}), /could not be sent/],
    ])("tells %p at the card, and gives the buttons back", async (error, text) => {
        await launch({answer: () => Promise.reject(error)})
        await fireEvent.press(await screen.findByTestId(`wait-${CONFIRM}-action-yes`))
        expect(await screen.findByText(text)).toBeOnTheScreen()
        expect(screen.getByTestId(`wait-${CONFIRM}-action-yes`)).toBeEnabled()
    })

    it.each([
        ["unavailable", /Set up Face ID/],
        ["refused", /did not take this phone's proof/],
        ["failed", /could not be confirmed/],
    ] as const)("sends nothing when the unlock is %s", async (outcome, text) => {
        const {answer} = await launch({outcome})
        await fireEvent.press(await screen.findByTestId(`wait-${CONFIRM}-action-yes`))
        expect(await screen.findByText(text)).toBeOnTheScreen()
        expect(answer).not.toHaveBeenCalled()
    })

    it("sends nothing and says nothing when the person cancels the biometrics", async () => {
        const {answer, unlock} = await launch({outcome: "cancelled"})
        await fireEvent.press(await screen.findByTestId(`wait-${CONFIRM}-action-yes`))
        await waitFor(() => {
            expect(unlock).toHaveBeenCalled()
        })
        await waitFor(() => {
            expect(screen.getByTestId(`wait-${CONFIRM}-action-yes`)).toBeEnabled()
        })
        expect(answer).not.toHaveBeenCalled()
        expect(screen.queryByRole("alert")).toBeNull()
    })

    it("says the server could not be reached when the unlock fails offline", async () => {
        const {answer} = await launch({outcome: new ApiError({kind: "network"})})
        await fireEvent.press(await screen.findByTestId(`wait-${CONFIRM}-action-yes`))
        expect(await screen.findByText(/Nothing was sent: try again/)).toBeOnTheScreen()
        expect(answer).not.toHaveBeenCalled()
    })

    it("signs out when the server no longer takes the token at the unlock", async () => {
        await launch({outcome: new ApiError({kind: "unauthorized"})})
        await fireEvent.press(await screen.findByTestId(`wait-${CONFIRM}-action-yes`))
        expect(await screen.findByText(/session has expired/)).toBeOnTheScreen()
    })

    it("leaves to the console an action that needs a field the app cannot fill", async () => {
        const id = "0b1f6c1e-0000-4000-8000-0000000000ad"
        const {answer} = await launch({
            open: [
                wait(id, {
                    title: "Secret",
                    fields: [{id: "key", type: "secret", label: "Key"}],
                    actions: [{id: "give", label: "Give", requires: ["key"]}, {id: "no", label: "Refuse"}],
                }),
            ],
        })
        expect(await screen.findByText(/answer it from the console/)).toBeOnTheScreen()
        expect(screen.getByTestId(`wait-${id}-action-give`)).toBeDisabled()
        await fireEvent.press(screen.getByTestId(`wait-${id}-action-no`))
        await waitFor(() => {
            expect(answer).toHaveBeenCalledWith(id, "no", {})
        })
    })

    it("shows a wait of a state it does not know without a button", async () => {
        await launch({open: [{...confirm, status: "unknown"}]})
        expect(await screen.findByText(/does not know this question's state/)).toBeOnTheScreen()
        expect(screen.queryByRole("button", {name: "Ship it"})).toBeNull()
    })

    it("opens a link of a wait, and only an https one", async () => {
        const id = "0b1f6c1e-0000-4000-8000-0000000000ae"
        const refs = [
            {kind: "url", url: "https://example.test/doc", title: "Design note"},
            {kind: "url", url: "http://example.test/plain"},
            {kind: "url", url: "javascript:alert(1)", title: "Script"},
            {kind: "session", id: "ses_1"},
            {kind: "artifact", id: "art_1"},
            {kind: "other"},
        ]
        const failing = jest.fn(() => Promise.reject(new Error("no browser")))
        const {openUrl} = await launch({open: [wait(id, {refs})], openUrl: failing})
        await fireEvent.press(await screen.findByRole("link", {name: "Open Design note"}))
        expect(openUrl).toHaveBeenCalledWith("https://example.test/doc")
        expect(screen.queryByRole("link", {name: /plain|Script/})).toBeNull()
        expect(screen.getByText("Session ses_1")).toBeOnTheScreen()
        expect(screen.getByText("Artifact art_1")).toBeOnTheScreen()
        expect(screen.getAllByText("Link")).toHaveLength(2)
    })

    it("offers to try again after a failure", async () => {
        const list = jest
            .fn<Promise<WaitsPage>, Parameters<WaitsRepository["list"]>>()
            .mockRejectedValueOnce(new ApiError({kind: "network"}))
            .mockRejectedValueOnce(new ApiError({kind: "network"}))
            .mockResolvedValue({items: [confirm]})
        await launch({list})
        expect(await screen.findByText("Your waits could not be loaded.")).toBeOnTheScreen()
        await fireEvent.press(screen.getByTestId("waits-retry"))
        expect(await screen.findByText("Ship the migration?")).toBeOnTheScreen()
    })

    it("asks for the organization when the server wants it named", async () => {
        const list = jest.fn(() => Promise.reject(refused(409, "org_required")))
        const {router} = await launch({list})
        await fireEvent.press(await screen.findByTestId("waits-choose-organization"))
        expect(router.getPathname()).toBe("/settings")
    })

    it("keeps the list it had when a refresh fails, and says it may be out of date", async () => {
        let fail = false
        const {list, client} = await launch({
            list: () => (fail ? Promise.reject(refused(409, "org_required")) : Promise.resolve({items: [confirm]})),
        })
        await screen.findByText("Ship the migration?")
        fail = true
        await act(async () => {
            await client.invalidateQueries()
        })
        expect(await screen.findByText(/may be out of date/)).toBeOnTheScreen()
        expect(screen.getByText(/choose it in Settings/)).toBeOnTheScreen()
        expect(screen.getByText("Ship the migration?")).toBeOnTheScreen()
        expect(screen.getByTestId("waits-choose-organization")).toBeOnTheScreen()
        expect(list).toHaveBeenCalled()
    })

    it("says only that the list may be out of date when the refresh fails otherwise", async () => {
        let fail = false
        const {client} = await launch({list: () => (fail ? Promise.reject(new ApiError({kind: "network"})) : Promise.resolve({items: [confirm]}))})
        await screen.findByText("Ship the migration?")
        fail = true
        await act(async () => {
            await client.invalidateQueries()
        })
        expect(await screen.findByText(/may be out of date/)).toBeOnTheScreen()
        expect(screen.queryByTestId("waits-choose-organization")).toBeNull()
    })

    it("shows older waits on demand, a keyset page at a time", async () => {
        const list = jest.fn((_filter: string, options?: {cursor?: string | undefined; limit?: number | undefined}) =>
            Promise.resolve(options?.cursor === undefined ? {items: [open], next: OPEN} : {items: [confirm]}),
        )
        await launch({list})
        await fireEvent.press(await screen.findByTestId("waits-more"))
        expect(await screen.findByText("Ship the migration?")).toBeOnTheScreen()
        expect(list).toHaveBeenCalledWith("open", {cursor: OPEN})
        expect(screen.queryByTestId("waits-more")).toBeNull()
        // The badge counts the first page, and says there is more.
        expect(screen.getAllByText("1+").length).toBeGreaterThan(0)
    })

    it("speaks the language of the app, and English for one it does not speak", async () => {
        await i18next.changeLanguage("fr")
        await launch()
        expect(await screen.findByText("On te demande")).toBeOnTheScreen()
        expect(screen.getByText("Répondues")).toBeOnTheScreen()
        expect(screen.getByLabelText("2 en attente")).toBeOnTheScreen()
    })

    it("falls back on English for a language it does not speak", async () => {
        await i18next.changeLanguage("de")
        await launch()
        expect(await screen.findByText("Asked of you")).toBeOnTheScreen()
    })

    it("shows the id of an action the question no longer has, and a closed wait that kept no answer", async () => {
        const gone = wait("0b1f6c1e-0000-4000-8000-0000000000af", {title: "Gone action"}, {status: "resolved", answer: {action: "ghost", author: "rev-b", answeredAt: "2026-11-13T10:00:00Z"}})
        const bare = wait("0b1f6c1e-0000-4000-8000-0000000000b0", {title: "No answer kept"}, {status: "resolved"})
        await launch({list: () => Promise.resolve({items: [gone, bare]})})
        expect(await screen.findByText(/^ghost · Answered by rev-b/)).toBeOnTheScreen()
        expect(screen.getByText("No answer kept")).toBeOnTheScreen()
    })

    it("draws a choice that only takes a free answer, and a withdrawn wait that kept no date", async () => {
        const id = "0b1f6c1e-0000-4000-8000-0000000000b1"
        const free = wait(id, {fields: [{id: "c", type: "choice", label: "Anything", allowOther: true}], actions: [{id: "ok", label: "OK"}]})
        const open1 = {...confirm, id: "0b1f6c1e-0000-4000-8000-0000000000b2", status: "cancelled" as const}
        const {answer} = await launch({open: [free], list: (filter) => Promise.resolve({items: filter === "cancelled" ? [open1] : [free]})})
        await fireEvent.changeText(await screen.findByTestId(`wait-${id}-c-other`), "whatever")
        await fireEvent.press(screen.getByRole("button", {name: "OK"}))
        await waitFor(() => {
            expect(answer).toHaveBeenCalledWith(id, "ok", {c: "whatever"})
        })
        await fireEvent.press(screen.getByTestId("waits-filter-cancelled"))
        expect(await screen.findByText("Ship the migration?")).toBeOnTheScreen()
        expect(screen.queryByText(/^Withdrawn/)).toBeNull()
    })
})
