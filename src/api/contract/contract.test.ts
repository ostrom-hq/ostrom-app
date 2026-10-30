import {ActivityKindSchema} from "./activity"
import {ReceiptEnvelopeSchema} from "./receipts"
import {CheckStateSchema} from "./yes"

describe("contract", () => {
    it("refuses a receipt envelope that is not a receipt", () => {
        const parsed = ReceiptEnvelopeSchema.safeParse({id: 1})
        expect(parsed.success).toBe(false)
    })

    it("reads unknown enum values of a newer server as their fallback", () => {
        expect(ActivityKindSchema.parse("agent.dreamed")).toBe("other")
        expect(CheckStateSchema.parse("timed_out")).toBe("unknown")
    })
})
