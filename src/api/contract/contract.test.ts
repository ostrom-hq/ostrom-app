import {activityKind} from "./activity"
import {ApprovalSchema, ApprovalViewSchema, needsBiometrics, phoneMayApprove} from "./approvals"
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

    it("reads a status, an action kind and a risk of a newer server without failing the request", () => {
        const action = {kind: "release", repo: "acme/acme-api", ref: "main", headSha: "c60f859", risk: "extreme"}
        const parsed = ApprovalSchema.parse({...approval, status: "withdrawn", action})
        expect(parsed.status).toBe("unknown")
        expect(parsed.action).toMatchObject({kind: "unknown", risk: "high"})
    })

    it("shows a request whose rules, receipt or gate status have a shape this app does not read", () => {
        const view = ApprovalViewSchema.parse({
            approval,
            gate: {workflowName: "Release", label: "Merge to main", status: "archived-later", approvers: 3},
            rules: [{id: "r1", strength: "absolute"}],
            receipt: {anything: true},
            you: {login: "rev-b", self: false, canApprove: true},
        })
        expect(view.gate).toEqual({workflowName: "Release", label: "Merge to main"})
        expect(view.approval.id).toBe("appr_1")
    })

    it("asks for biometrics unless the action is low risk, and takes no yes on a truncated diff", () => {
        const action = {kind: "merge", repo: "acme/acme-api", ref: "main", headSha: "c60f859"}
        const low = ApprovalSchema.parse({...approval, action: {...action, risk: "low"}})
        expect(needsBiometrics(low)).toBe(false)
        expect(needsBiometrics(ApprovalSchema.parse({...approval, action: {...action, risk: "high"}}))).toBe(true)
        expect(needsBiometrics(ApprovalSchema.parse(approval))).toBe(true)
        expect(phoneMayApprove(low)).toBe(true)
        expect(phoneMayApprove(ApprovalSchema.parse({...approval, diffTruncated: true}))).toBe(false)
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
