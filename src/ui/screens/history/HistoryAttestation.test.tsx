// SPDX-License-Identifier: AGPL-3.0-only
import {screen} from "@testing-library/react-native"
import {ReceiptKeysSchema, type ReceiptListItem, type ReceiptPage, ReceiptPageSchema, type ReceiptView} from "@/api/contract/receipts"
import {receiptKey, receipts} from "@/api/mocks/fixtures"
import {fakeRepositories, signedInServices} from "@/test/fakeServices"
import {renderWithServices} from "@/test/renderWithServices"
import {lightTheme} from "@/ui/theme/theme"
import {HistoryScreen} from "./HistoryScreen"

// Every signature taken as verified: what a proven origin looks like, which no golden vector carries yet.
jest.mock("./receiptCheck", () => ({checkReceipt: () => ({kind: "verified", kid: "k"})}))

const {colors} = lightTheme
const golden = ReceiptPageSchema.parse({items: receipts}).items

function workflow(): ReceiptListItem {
    const [first] = golden
    if (first?.readable !== true) throw new Error("the vectors start with a readable receipt")
    type Node = NonNullable<ReceiptView["nodes"]>[number]
    const node = (id: string, label: string, attestation: Node["attestation"]): Node => ({
        id,
        workflowId: "wf_1",
        kind: "check",
        label,
        outcome: "passed",
        attestation,
    })
    const nodes = [
        node("n1", "Unit tests", "workstation_declared"),
        node("n2", "Lint", "isolated_runner"),
        node("n3", "Build", "ci"),
        node("n4", "Format", "unknown"),
    ]
    return {readable: true, raw: {}, view: {...first.view, id: "r-workflow", nodes}}
}

describe("HistoryScreen on a verified receipt", () => {
    it("marks Ostrom and the CI as proven, and the workstation's word or an unknown origin as not", async () => {
        const services = await signedInServices({
            repositories: fakeRepositories({
                receipts: {
                    list: jest.fn((): Promise<ReceiptPage> => Promise.resolve({items: [workflow()], next: undefined})),
                    get: jest.fn(),
                    keys: jest.fn(() => Promise.resolve(ReceiptKeysSchema.parse({keys: [receiptKey]}).keys)),
                },
            }),
        })
        await renderWithServices(<HistoryScreen />, services)
        const proven = {color: colors.signalText, backgroundColor: colors.signalTint}
        const unproven = {color: colors.warningText, backgroundColor: colors.warningTint}
        expect(await screen.findByText("Run by Ostrom")).toHaveStyle(proven)
        expect(screen.getByText("Run by the CI")).toHaveStyle(proven)
        expect(screen.getByText("Declared by the workstation")).toHaveStyle(unproven)
        expect(screen.getByText("Origin unknown to this app")).toHaveStyle(unproven)
    })
})
