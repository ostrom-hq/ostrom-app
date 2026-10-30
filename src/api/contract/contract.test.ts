import {activityKind} from "./activity"
import {ApprovalSchema} from "./approvals"
import {PushDataSchema} from "./notifications"
import {ReceiptEnvelopeSchema, ReceiptPageSchema} from "./receipts"

const approval = {id: "appr_1", title: "Merge PR #6", requestedFrom: "rev-b", channel: "app", status: "pending", shownDigest: "d"}

describe("contract", () => {
    it("refuses a receipt envelope that is not a receipt", () => {
        expect(ReceiptEnvelopeSchema.safeParse({id: 1}).success).toBe(false)
    })

    it("keeps an unreadable receipt of a page instead of failing the page", () => {
        const page = ReceiptPageSchema.parse({items: [{id: 1}]})
        expect(page.items.map((item) => item.readable)).toEqual([false])
        expect(page.next).toBeUndefined()
    })

    it("reads a check state of a newer server as unknown, never as a pass", () => {
        const parsed = ApprovalSchema.parse({...approval, checks: [{name: "ci", state: "timed_out"}, {name: "lint", state: "success"}]})
        expect(parsed.checks?.map((check) => check.state)).toEqual(["unknown", "success"])
    })

    it("reads an activity kind of a newer server as other", () => {
        expect(activityKind("agent.dreamed")).toBe("other")
        expect(activityKind("gate.decided")).toBe("gate.decided")
    })

    it("opens only an Ostrom push whose id cannot be a path", () => {
        expect(PushDataSchema.safeParse({kind: "approval", approvalId: "appr_1"}).success).toBe(true)
        expect(PushDataSchema.safeParse({kind: "approval", approvalId: "../settings"}).success).toBe(false)
        expect(PushDataSchema.safeParse({kind: "ask", askId: "appr_1"}).success).toBe(false)
    })
})
