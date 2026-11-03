// SPDX-License-Identifier: AGPL-3.0-only
import type {QueryClient} from "@tanstack/react-query"
import {act, fireEvent, screen, within} from "@testing-library/react-native"
import {ReceiptKeysSchema, type ReceiptListItem, type ReceiptPage, ReceiptPageSchema, type ReceiptPublicKey} from "@/api/contract/receipts"
import {receiptKey, receipts} from "@/api/mocks/fixtures"
import {base64ToBytes} from "@/domain/receipts/bytes"
import {parseEd25519PublicKeyPem} from "@/domain/receipts/publicKey"
import {bytesToBase64} from "@/test/base64"
import {fakeRepositories, signedInServices} from "@/test/fakeServices"
import {renderWithServices} from "@/test/renderWithServices"
import {HistoryScreen} from "./HistoryScreen"
import * as receiptCheck from "./receiptCheck"

// The checks a row asks for wait here until the test lets them run: what a row shows before its result is seen.
jest.mock("./scheduleCheck", () => {
    const mockQueue: (() => void)[] = []
    return {
        mockQueue,
        scheduleCheck: (task: () => void) => {
            mockQueue.push(task)
            return () => {
                const index = mockQueue.indexOf(task)
                if (index >= 0) mockQueue.splice(index, 1)
            }
        },
    }
})
const {mockQueue} = jest.requireMock<{mockQueue: (() => void)[]}>("./scheduleCheck")
// The real verification, counted.
const verifications = jest.spyOn(receiptCheck, "checkReceipt")

const CHECKING = "Checking the signature on this phone…"
const VERIFIED = `Signature verified on this phone · key ${receiptKey.kid}`
const golden = ReceiptPageSchema.parse({items: receipts}).items
const {keys: served} = ReceiptKeysSchema.parse({keys: [receiptKey]})

function firstGolden(): Extract<ReceiptListItem, {readable: true}> {
    const [first] = golden
    if (first?.readable !== true) throw new Error("the vectors start with a readable receipt")
    return first
}

/** The first golden receipt, changed after signing: same id, another content. */
function tampered(): ReceiptListItem {
    const first = firstGolden()
    const raw: unknown = first.raw
    if (typeof raw !== "object" || raw === null) throw new Error("receipt is not an object")
    return {...first, raw: {...raw, initiator: "mallory"}}
}

/** Another Ed25519 key, announced under its own id: the instance rotated its key. */
function rotatedKey(): ReceiptPublicKey {
    const body = /-----BEGIN PUBLIC KEY-----([\s\S]+?)-----END PUBLIC KEY-----/.exec(receiptKey.publicKeyPem)?.[1] ?? ""
    const der = base64ToBytes(body)
    der[43] = (der[43] ?? 0) ^ 1
    const publicKeyPem = `-----BEGIN PUBLIC KEY-----\n${bytesToBase64(der)}\n-----END PUBLIC KEY-----\n`
    return {...receiptKey, kid: parseEd25519PublicKeyPem(publicKeyPem).kid, publicKeyPem}
}

function page(items: readonly ReceiptListItem[]): Promise<ReceiptPage> {
    return Promise.resolve({items: [...items], next: undefined})
}

async function show(
    list: () => Promise<ReceiptPage>,
    keys: () => Promise<readonly ReceiptPublicKey[]> = () => Promise.resolve(served),
): Promise<QueryClient> {
    const services = await signedInServices({repositories: fakeRepositories({receipts: {list: jest.fn(list), get: jest.fn(), keys: jest.fn(keys)}})})
    return renderWithServices(<HistoryScreen />, services)
}

/** Lets the checks the rows asked for run, as the phone does once a frame is drawn. */
async function runChecks(): Promise<void> {
    await act(async () => {
        for (let task = mockQueue.shift(); task !== undefined; task = mockQueue.shift()) task()
        await Promise.resolve()
    })
}

beforeEach(() => {
    mockQueue.length = 0
    verifications.mockClear()
})

describe("History, each receipt checked when its row is shown", () => {
    it("checks no receipt before its row asks, and shows a neutral line, never verified, until the result", async () => {
        await show(() => page(golden))
        expect(await screen.findAllByText(CHECKING)).toHaveLength(golden.length)
        expect(screen.queryAllByText(VERIFIED)).toHaveLength(0)
        // A proven origin is never shown before the signature is.
        expect(screen.queryAllByText("Run by Ostrom")).toHaveLength(0)
        expect(verifications).not.toHaveBeenCalled()

        await runChecks()
        expect(screen.getAllByText(VERIFIED)).toHaveLength(golden.length)
        expect(screen.queryAllByText(CHECKING)).toHaveLength(0)
        expect(verifications.mock.calls.length).toBeLessThanOrEqual(screen.getAllByTestId(/^receipt-/).length)
    })

    it("flags a receipt changed after signing on the first screen, and after a scroll, even under a verified receipt's id", async () => {
        const first = firstGolden()
        const copies = 3
        const filler = Array.from({length: copies}, () => golden).flat()
        await show(() => page([tampered(), ...filler, tampered()]))
        await screen.findAllByText(CHECKING)
        await runChecks()
        const [top] = screen.getAllByTestId(`receipt-${first.view.id}`)
        if (top === undefined) throw new Error("no first row")
        expect(within(top).getByText("Signature rejected: the content changed after signing")).toBeOnTheScreen()

        // Down to the last row, which mounts once the cache holds the genuine receipt under the same id. The test
        // renderer lays nothing out: the list and each row it shows are given a size, 100 points a row, then scrolled
        // to the end, until the last row is there.
        const list = screen.getByTestId("history-list")
        const total = filler.length + 2
        const sameId = copies + 2
        await fireEvent(list, "layout", {nativeEvent: {layout: {x: 0, y: 0, width: 400, height: 800}}})
        await fireEvent(list, "contentSizeChange", 400, total * 100)
        for (let pass = 0; pass < 10 && screen.getAllByTestId(`receipt-${first.view.id}`).length < sameId; pass += 1) {
            for (const [index, row] of screen.getAllByTestId(/^receipt-/).entries()) {
                // Bubbles up to the list's cell around the row.
                await fireEvent(row, "layout", {nativeEvent: {layout: {x: 0, y: index * 100, width: 400, height: 100}}})
            }
            await fireEvent.scroll(list, {
                nativeEvent: {
                    contentOffset: {x: 0, y: total * 100 - 800},
                    contentSize: {width: 400, height: total * 100},
                    layoutMeasurement: {width: 400, height: 800},
                },
            })
            await act(async () => {
                await new Promise((resolve) => setTimeout(resolve, 100))
            })
        }
        const rows = screen.getAllByTestId(`receipt-${first.view.id}`)
        expect(rows).toHaveLength(sameId)
        const last = rows.at(-1)
        if (last === undefined) throw new Error("no last row")
        await runChecks()
        expect(within(last).getByText("Signature rejected: the content changed after signing")).toBeOnTheScreen()
        expect(within(last).queryByText(VERIFIED)).toBeNull()
    })

    it("keeps each result by receipt: a refresh with the same receipts checks nothing again and shows no neutral line", async () => {
        const client = await show(() => page(golden))
        await screen.findAllByText(CHECKING)
        await runChecks()
        const checked = verifications.mock.calls.length
        expect(checked).toBeGreaterThan(0)

        await act(async () => {
            await client.invalidateQueries()
        })
        await runChecks()
        expect(screen.getAllByText(VERIFIED)).toHaveLength(golden.length)
        expect(screen.queryAllByText(CHECKING)).toHaveLength(0)
        expect(verifications.mock.calls.length).toBe(checked)
    })

    it("forgets every result when the instance key changes: neutral again, then checked with the new key", async () => {
        let current: readonly ReceiptPublicKey[] = served
        const client = await show(
            () => page(golden),
            () => Promise.resolve(current),
        )
        await screen.findAllByText(CHECKING)
        await runChecks()
        expect(screen.getAllByText(VERIFIED)).toHaveLength(golden.length)

        current = [rotatedKey()]
        await act(async () => {
            await client.invalidateQueries()
        })
        expect(await screen.findAllByText(CHECKING)).toHaveLength(golden.length)
        expect(screen.queryAllByText(VERIFIED)).toHaveLength(0)
        await runChecks()
        // The receipts were signed with the key before: the new one does not verify them.
        expect(screen.queryAllByText(VERIFIED)).toHaveLength(0)
        expect(screen.getAllByText(/^Signature rejected: /)).toHaveLength(golden.length)
    })
})
