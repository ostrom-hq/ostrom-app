// SPDX-License-Identifier: AGPL-3.0-only
import type {QueryClient} from "@tanstack/react-query"
import {act, fireEvent, screen} from "@testing-library/react-native"
import {
    ReceiptKeysSchema,
    type ReceiptListItem,
    type ReceiptPage,
    ReceiptPageSchema,
    type ReceiptPublicKey,
    type ReceiptView,
} from "@/api/contract/receipts"
import {ApiError} from "@/api/http/ApiError"
import {receiptKey, receipts} from "@/api/mocks/fixtures"
import {fakeRepositories, signedInServices} from "@/test/fakeServices"
import {renderWithServices} from "@/test/renderWithServices"
import {lightTheme} from "@/ui/theme/theme"
import {HistoryScreen} from "./HistoryScreen"
import {toReceiptRow} from "./useHistoryViewModel"

const golden = ReceiptPageSchema.parse({items: receipts}).items
const {keys: served} = ReceiptKeysSchema.parse({keys: [receiptKey]})

function page(items: readonly ReceiptListItem[]): Promise<ReceiptPage> {
    return Promise.resolve({items: [...items], next: undefined})
}

async function show(
    list: jest.Mock<Promise<ReceiptPage>, []>,
    keys: jest.Mock<Promise<readonly ReceiptPublicKey[]>, []> = jest.fn(() => Promise.resolve(served)),
): Promise<QueryClient> {
    const services = await signedInServices({
        repositories: fakeRepositories({receipts: {list, get: jest.fn(), keys}}),
    })
    return renderWithServices(<HistoryScreen />, services)
}

function view(overrides: Partial<ReceiptView>): ReceiptView {
    const [first] = golden
    if (first?.readable !== true) throw new Error("the vectors start with a readable receipt")
    return {...first.view, ...overrides}
}

describe("HistoryScreen", () => {
    it("verifies every golden receipt on the phone", async () => {
        await show(jest.fn(() => page(golden)))
        const verified = await screen.findAllByText(`Signature verified on this phone · key ${receiptKey.kid}`)
        expect(verified).toHaveLength(golden.length)
    })

    it("flags a receipt changed after signing, and one it cannot display", async () => {
        const [first] = golden
        if (first?.readable !== true) throw new Error("the vectors start with a readable receipt")
        const raw: unknown = first.raw
        if (typeof raw !== "object" || raw === null) throw new Error("receipt is not an object")
        const tampered: ReceiptListItem = {...first, raw: {...raw, initiator: "mallory"}}
        const unreadable: ReceiptListItem = {readable: false, raw: "a newer shape", detail: "newer shape"}
        await show(jest.fn(() => page([tampered, unreadable])))
        expect(await screen.findByText("Signature rejected: the content changed after signing")).toBeOnTheScreen()
        expect(screen.getByText("A receipt this version of the app cannot display")).toBeOnTheScreen()
        expect(screen.getByText("Signature rejected: not a receipt")).toBeOnTheScreen()
    })

    it("never claims a verification without the instance key", async () => {
        await show(
            jest.fn(() => page(golden)),
            jest.fn(() => Promise.reject(new ApiError({kind: "network"}))),
        )
        expect((await screen.findAllByText("Not verified: the instance key is unavailable")).length).toBe(golden.length)
    })

    it("never claims a verification with a key the server did not announce", async () => {
        // The key's PEM is the instance key, but the server names it another id: it is left out.
        await show(
            jest.fn(() => page(golden)),
            jest.fn(() => Promise.resolve(served.map((key) => ({...key, kid: "another-key"})))),
        )
        expect((await screen.findAllByText("Not verified: the instance key is unavailable")).length).toBe(golden.length)
    })

    it("names the decision in the person's words, or says who it was signed for", async () => {
        const decided = (result: string, by = "rev-b", selfApproved = false): ReceiptListItem => {
            const decision = {approvalId: "a", by, at: "2026-10-30T09:00:00.000Z", result, selfApproved}
            return {readable: true, raw: {}, view: view({id: `r-${result}`, decisions: [decision]})}
        }
        const signedOnly: ReceiptListItem = {readable: true, raw: {}, view: view({id: "r-none", decisions: [], initiator: "dev-a"})}
        // A server bug sends the same receipt twice: both rows are shown.
        const twice = decided("approved", "dev-a", true)
        await show(jest.fn(() => page([decided("approved"), decided("rejected"), decided("merged"), signedOnly, twice, twice])))
        expect(await screen.findByText("Approved by rev-b")).toBeOnTheScreen()
        expect(screen.getByText("Declined by rev-b")).toBeOnTheScreen()
        // A result this app does not know is not shown raw inside the sentence.
        expect(screen.getByText("Decided by rev-b")).toBeOnTheScreen()
        expect(screen.getByText("Signed for dev-a")).toBeOnTheScreen()
        expect(screen.getAllByText("Approved by dev-a · self-approved")).toHaveLength(2)
    })

    it("never words an origin as fact on a receipt whose signature is not verified", async () => {
        type Node = NonNullable<ReceiptView["nodes"]>[number]
        const node = (id: string, kind: string, label: string, outcome: string): Node => ({id, workflowId: "wf_1", kind, label, outcome})
        const workflow: ReceiptListItem = {
            readable: true,
            raw: {},
            view: view({
                id: "r-workflow",
                nodes: [
                    {...node("n1", "check", "Unit tests", "passed"), attestation: "workstation_declared"},
                    {...node("n2", "check", "Lint", "passed"), attestation: "isolated_runner"},
                    {...node("n3", "check", "Build", "failed: action blocked (2 runs)"), attestation: "ci"},
                    // Signed before the field existed: the workstation's word.
                    node("n4", "check", "Format", "passed"),
                    node("n5", "agent", "Reviewer", "delivered (sub-agent or skill), not reported"),
                ],
            }),
        }
        await show(jest.fn(() => page([workflow])))
        expect(await screen.findByText("Unit tests · passed")).toBeOnTheScreen()
        expect(screen.getAllByText("Declared by the workstation")).toHaveLength(2)
        expect(screen.getByText("Build · failed: action blocked (2 runs)")).toBeOnTheScreen()
        // The signature of these rows is not verified (`raw` is not the signed receipt): Ostrom and the CI are only
        // claimed, and say so.
        const claimed = screen.getAllByText("Origin not verified")
        expect(claimed).toHaveLength(2)
        for (const badge of claimed) {
            expect(badge).toHaveStyle({color: lightTheme.colors.warningText})
        }
        expect(screen.queryByText(/^Run by/)).toBeNull()
        // Only checks carry an origin.
        expect(screen.queryByText(/Reviewer/)).toBeNull()
    })

    it("says when there is no receipt yet", async () => {
        await show(jest.fn(() => page([])))
        expect(await screen.findByText("No receipt yet.")).toBeOnTheScreen()
    })

    it("offers to try again, and fetches the key again if it failed too", async () => {
        const list = jest
            .fn<Promise<ReceiptPage>, []>()
            .mockRejectedValueOnce(new ApiError({kind: "network"}))
            .mockImplementation(() => page(golden))
        const keys = jest
            .fn<Promise<readonly ReceiptPublicKey[]>, []>()
            .mockRejectedValueOnce(new ApiError({kind: "network"}))
            .mockResolvedValue(served)
        await show(list, keys)
        await fireEvent.press(await screen.findByRole("button", {name: "Try again"}))
        expect((await screen.findAllByText(`Signature verified on this phone · key ${receiptKey.kid}`)).length).toBe(golden.length)
        expect(keys).toHaveBeenCalledTimes(2)
    })

    it("keeps the receipts read before when a refresh fails, and says so", async () => {
        const list = jest
            .fn<Promise<ReceiptPage>, []>()
            .mockImplementationOnce(() => page(golden))
            .mockRejectedValue(new ApiError({kind: "network"}))
        const client = await show(list)
        expect((await screen.findAllByText(`Signature verified on this phone · key ${receiptKey.kid}`)).length).toBe(golden.length)
        await act(async () => {
            await client.invalidateQueries()
        })
        expect(await screen.findByRole("alert")).toHaveTextContent(/could not be refreshed/)
        expect(screen.getAllByText(`Signature verified on this phone · key ${receiptKey.kid}`)).toHaveLength(golden.length)
    })

    it("reads the keys again with the list, so a rotated key is picked up", async () => {
        const list = jest
            .fn<Promise<ReceiptPage>, []>()
            .mockRejectedValueOnce(new ApiError({kind: "server", status: 503}))
            .mockImplementation(() => page([]))
        const keys = jest.fn(() => Promise.resolve(served))
        await show(list, keys)
        await fireEvent.press(await screen.findByRole("button", {name: "Try again"}))
        expect(await screen.findByText("No receipt yet.")).toBeOnTheScreen()
        expect(keys).toHaveBeenCalledTimes(2)
    })
})

describe("toReceiptRow", () => {
    it("dates a receipt without decision by its creation", () => {
        const row = toReceiptRow({readable: true, raw: {}, view: view({decisions: [], createdAt: "2026-10-29T10:00:00.000Z"})}, 0, {kind: "no-key"})
        expect(row).toMatchObject({readable: true, at: "2026-10-29T10:00:00.000Z", result: undefined})
    })

    it("proves Ostrom or the CI only on a verified signature", () => {
        const nodes = [
            {id: "n1", workflowId: "wf_1", kind: "check", label: "Build", outcome: "passed", attestation: "ci" as const},
            {id: "n2", workflowId: "wf_1", kind: "check", label: "Lint", outcome: "passed"},
        ]
        const item: ReceiptListItem = {readable: true, raw: {}, view: view({nodes})}
        expect(toReceiptRow(item, 0, {kind: "verified", kid: "k"})).toMatchObject({
            checkRuns: [
                {label: "Build", badge: "ci", proven: true},
                {label: "Lint", badge: "workstation_declared", proven: false},
            ],
        })
        expect(toReceiptRow(item, 0, {kind: "rejected", reason: "bad-signature"})).toMatchObject({
            checkRuns: [
                {label: "Build", badge: "unverified", proven: false},
                {label: "Lint", badge: "workstation_declared", proven: false},
            ],
        })
    })

    it("lists no check for a receipt without nodes", () => {
        const row = toReceiptRow({readable: true, raw: {}, view: view({nodes: undefined})}, 0, {kind: "no-key"})
        expect(row).toMatchObject({readable: true, checkRuns: []})
    })
})
