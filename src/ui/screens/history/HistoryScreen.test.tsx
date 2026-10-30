import {fireEvent, screen} from "@testing-library/react-native"
import {type ReceiptListItem, ReceiptListSchema, type ReceiptPublicKey, ReceiptPublicKeySchema, type ReceiptView} from "@/api/contract/receipts"
import {ApiError} from "@/api/http/ApiError"
import {receiptKey, receipts} from "@/api/mocks/fixtures"
import {fakeRepositories, signedInServices} from "@/test/fakeServices"
import {renderWithServices} from "@/test/renderWithServices"
import {HistoryScreen} from "./HistoryScreen"
import {toReceiptRow} from "./useHistoryViewModel"

const golden = ReceiptListSchema.parse(receipts)
const served = ReceiptPublicKeySchema.parse(receiptKey)

async function show(
    list: jest.Mock<Promise<readonly ReceiptListItem[]>, []>,
    publicKey: jest.Mock<Promise<ReceiptPublicKey>, []> = jest.fn(() => Promise.resolve(served)),
): Promise<void> {
    const services = await signedInServices({
        repositories: fakeRepositories({receipts: {list, get: jest.fn(), publicKey}}),
    })
    await renderWithServices(<HistoryScreen />, services)
}

function view(overrides: Partial<ReceiptView>): ReceiptView {
    const [first] = golden
    if (first?.readable !== true) throw new Error("the vectors start with a readable receipt")
    return {...first.view, ...overrides}
}

describe("HistoryScreen", () => {
    it("verifies every golden receipt on the phone", async () => {
        await show(jest.fn(() => Promise.resolve(golden)))
        const verified = await screen.findAllByText(`Signature verified on this phone · key ${served.kid}`)
        expect(verified).toHaveLength(golden.length)
    })

    it("flags a receipt changed after signing, and one it cannot display", async () => {
        const [first] = golden
        if (first?.readable !== true) throw new Error("the vectors start with a readable receipt")
        const raw: unknown = first.raw
        if (typeof raw !== "object" || raw === null) throw new Error("receipt is not an object")
        const tampered: ReceiptListItem = {...first, raw: {...raw, initiator: "mallory"}}
        const unreadable: ReceiptListItem = {readable: false, raw: "a newer shape", detail: "newer shape"}
        await show(jest.fn(() => Promise.resolve([tampered, unreadable])))
        expect(await screen.findByText("Signature rejected: the content changed after signing")).toBeOnTheScreen()
        expect(screen.getByText("A receipt this version of the app cannot display")).toBeOnTheScreen()
        expect(screen.getByText("Signature rejected: not a receipt")).toBeOnTheScreen()
    })

    it("never claims a verification without the instance key", async () => {
        await show(
            jest.fn(() => Promise.resolve(golden)),
            jest.fn(() => Promise.reject(new ApiError({kind: "network"}))),
        )
        expect((await screen.findAllByText("Not verified: the instance key is unavailable")).length).toBe(golden.length)
    })

    it("names the decision in the person's words, or says who it was signed for", async () => {
        const decided = (result: string): ReceiptListItem => {
            const v = view({id: `r-${result}`, decisions: [{approvalId: "a", by: "rev-b", at: "2026-10-30T09:00:00.000Z", result}]})
            return {readable: true, raw: {}, view: v}
        }
        const signedOnly: ReceiptListItem = {readable: true, raw: {}, view: view({id: "r-none", decisions: [], initiator: "dev-a"})}
        await show(jest.fn(() => Promise.resolve([decided("approved"), decided("rejected"), decided("merged"), signedOnly])))
        expect(await screen.findByText("Approved by rev-b")).toBeOnTheScreen()
        expect(screen.getByText("Rejected by rev-b")).toBeOnTheScreen()
        expect(screen.getByText("merged by rev-b")).toBeOnTheScreen()
        expect(screen.getByText("Signed for dev-a")).toBeOnTheScreen()
    })

    it("says when there is no receipt yet", async () => {
        await show(jest.fn(() => Promise.resolve([])))
        expect(await screen.findByText("No receipt yet.")).toBeOnTheScreen()
    })

    it("offers to try again, and fetches the key again if it failed too", async () => {
        const list = jest
            .fn<Promise<readonly ReceiptListItem[]>, []>()
            .mockRejectedValueOnce(new ApiError({kind: "network"}))
            .mockResolvedValue(golden)
        const publicKey = jest
            .fn<Promise<ReceiptPublicKey>, []>()
            .mockRejectedValueOnce(new ApiError({kind: "network"}))
            .mockResolvedValue(served)
        await show(list, publicKey)
        await fireEvent.press(await screen.findByRole("button", {name: "Try again"}))
        expect((await screen.findAllByText(`Signature verified on this phone · key ${served.kid}`)).length).toBe(golden.length)
        expect(publicKey).toHaveBeenCalledTimes(2)
    })

    it("tries the list again alone when the key was fine", async () => {
        const list = jest
            .fn<Promise<readonly ReceiptListItem[]>, []>()
            .mockRejectedValueOnce(new ApiError({kind: "server", status: 503}))
            .mockResolvedValue([])
        const publicKey = jest.fn(() => Promise.resolve(served))
        await show(list, publicKey)
        await fireEvent.press(await screen.findByRole("button", {name: "Try again"}))
        expect(await screen.findByText("No receipt yet.")).toBeOnTheScreen()
        expect(publicKey).toHaveBeenCalledTimes(1)
    })
})

describe("toReceiptRow", () => {
    it("dates a receipt without decision by its creation", () => {
        const row = toReceiptRow({readable: true, raw: {}, view: view({decisions: [], createdAt: "2026-10-29T10:00:00.000Z"})}, 0, {kind: "no-key"})
        expect(row.at).toBe("2026-10-29T10:00:00.000Z")
        expect(row.result).toBeUndefined()
    })
})
