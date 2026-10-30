import {http as httpMock, HttpResponse} from "msw"
import {setupServer} from "msw/node"
import {HttpClientImpl} from "@/api/http/HttpClientImpl"
import {ApiError} from "@/api/http/ApiError"
import {MOCK_BASE_URL, notificationSettings, pendingApproval, receipts, selfApproval} from "@/api/mocks/fixtures"
import {createMockApi} from "@/api/mocks/handlers"
import {AccountRepositoryImpl} from "./AccountRepositoryImpl"
import {ActivityRepositoryImpl} from "./ActivityRepositoryImpl"
import {ApprovalsRepositoryImpl} from "./ApprovalsRepositoryImpl"
import {NotificationsRepositoryImpl} from "./NotificationsRepositoryImpl"
import {ReceiptsRepositoryImpl} from "./ReceiptsRepositoryImpl"
import {SpendingRepositoryImpl} from "./SpendingRepositoryImpl"

const server = setupServer()
const http = new HttpClientImpl({baseUrl: MOCK_BASE_URL, token: () => "ost_mock_token"})

beforeAll(() => {
    server.listen({onUnhandledRequest: "error"})
})
beforeEach(() => {
    server.resetHandlers(...createMockApi().handlers)
})
afterAll(() => {
    server.close()
})

async function refusal(call: Promise<unknown>): Promise<string> {
    try {
        await call
    } catch (error) {
        if (error instanceof ApiError && error.problem.kind === "refused") return error.problem.type
        throw error
    }
    throw new Error("the call was not refused")
}

describe("ApprovalsRepositoryImpl", () => {
    const approvals = new ApprovalsRepositoryImpl(http)

    it("reads the yes queue: what waits for my answer, then what was answered lately", async () => {
        const queue = await approvals.queue()
        expect(queue.operator).toBe("rev-b")
        expect(queue.pending.map((view) => view.approval.id)).toEqual(["appr_0001", "appr_0002", "appr_0003"])
        expect(queue.recent.map((view) => view.approval.id)).toEqual(["appr_0000"])
        // My own request is mine, and not mine to approve: the server says why.
        expect(queue.pending[1]?.you).toMatchObject({self: true, canApprove: false, reason: expect.any(String) as unknown})
    })

    it("reads one request with its evidence, commits and checks, and who asks through which gate", async () => {
        const view = await approvals.view("appr_0001")
        expect(view.approval.commitDetails).toHaveLength(2)
        expect(view.approval.checks?.map((check) => check.state)).toEqual(["success", "success", "pending"])
        expect(view).toMatchObject({who: {agent: "claude-code"}, gate: {label: "Merge to main"}, you: {canApprove: true}})
        expect(await refusal(approvals.view("nope"))).toBe("not_found")
    })

    it("approves a request bound to what was shown", async () => {
        const {approval} = await approvals.answer("appr_0001", "approve", pendingApproval.shownDigest)
        expect(await approvals.view("appr_0001")).toMatchObject({approval: {status: "approved"}})
        expect(approval).toMatchObject({status: "approved", decidedBy: "rev-b"})
        expect(await refusal(approvals.answer("appr_0001", "reject", pendingApproval.shownDigest, "Too late"))).toBe("conflict")
    })

    it("refuses a yes on a request that changed since it was shown", async () => {
        expect(await refusal(approvals.answer("appr_0001", "approve", "stale"))).toBe("shown_digest_mismatch")
    })

    it("sends the reason of a no with it", async () => {
        let sent: unknown
        server.use(
            httpMock.post(`${MOCK_BASE_URL}/api/v1/approvals/:id/answer`, async ({request}) => {
                sent = await request.json()
                return HttpResponse.json({approval: {...pendingApproval, status: "rejected"}, you: {login: "rev-b", self: false, canApprove: false}})
            }),
        )
        await approvals.answer("appr_0001", "reject", "d", "Wait for the migration PR")
        expect(sent).toEqual({answer: "reject", shownDigest: "d", note: "Wait for the migration PR"})
    })

    it("refuses my own yes when someone else can approve, but takes my no", async () => {
        expect(await refusal(approvals.answer("appr_0002", "approve", selfApproval.shownDigest))).toBe("forbidden")
        await expect(approvals.answer("appr_0002", "reject", selfApproval.shownDigest, "Not this one")).resolves.toMatchObject({
            approval: {status: "rejected"},
        })
    })

    it("refuses an unknown request or a malformed answer", async () => {
        expect(await refusal(approvals.answer("nope", "approve", "x"))).toBe("not_found")
        const raw = await fetch(`${MOCK_BASE_URL}/api/v1/approvals/appr_0001/answer`, {method: "POST", body: "{}"})
        expect(raw.status).toBe(400)
    })
})

describe("ReceiptsRepositoryImpl", () => {
    const repository = new ReceiptsRepositoryImpl(http)

    it("keeps each receipt raw next to its typed view", async () => {
        const {items} = await repository.list()
        expect(items).toHaveLength(receipts.length)
        const [first] = items
        expect(first?.raw).toEqual(receipts[0])
        expect(first?.readable === true ? first.view.signature : "").toMatch(/^ed25519;kid=/)
    })

    it("keeps an unreadable receipt in the list instead of failing it", async () => {
        server.use(httpMock.get(`${MOCK_BASE_URL}/api/v1/receipts`, () => HttpResponse.json({items: [receipts[0], {id: 1}]})))
        const {items} = await repository.list()
        expect(items.map((item) => item.readable)).toEqual([true, false])
        expect(items[1]?.readable === false ? items[1].detail : "").toContain("id")
    })

    it("reads one receipt and the instance keys", async () => {
        const [first] = (await repository.list()).items
        if (first?.readable !== true) throw new Error("no readable receipt")
        await expect(repository.get(first.view.id)).resolves.toEqual(first)
        expect(await refusal(repository.get("nope"))).toBe("not_found")
        await expect(repository.keys()).resolves.toEqual([expect.objectContaining({alg: "ed25519", kid: "b918e282a924dda0"})])
    })
})

describe("AccountRepositoryImpl", () => {
    const account = new AccountRepositoryImpl(http)

    it("reads who I am", async () => {
        await expect(account.me()).resolves.toMatchObject({dev: {login: "rev-b"}, member: {name: "Reviewer B"}})
    })

    it("trades the sign-in code for a token, and refuses a wrong code", async () => {
        const request = {codeVerifier: "v", redirectUri: "ostrom://auth/callback"}
        await expect(account.exchangeCode({...request, code: "good-code"})).resolves.toMatchObject({token: "ost_mock_token"})
        expect(await refusal(account.exchangeCode({...request, code: "bad"}))).toBe("invalid_request")
        const raw = await fetch(`${MOCK_BASE_URL}/api/v1/auth/mobile/token`, {method: "POST", body: "{}"})
        expect(raw.status).toBe(400)
    })

    it("revokes the token of the session", async () => {
        await expect(account.logout()).resolves.toBeUndefined()
    })
})

describe("NotificationsRepositoryImpl", () => {
    const notifications = new NotificationsRepositoryImpl(http)

    it("registers and unregisters this phone", async () => {
        const device = await notifications.registerDevice({
            pushToken: "ExponentPushToken[x]",
            provider: "expo",
            platform: "ios",
            locale: "fr",
        })
        expect(device).toMatchObject({id: "dev_1", provider: "expo", platform: "ios"})
        await expect(notifications.unregisterDevice(device.id)).resolves.toBeUndefined()
        const raw = await fetch(`${MOCK_BASE_URL}/api/v1/push-devices`, {method: "POST", body: "{}"})
        expect(raw.status).toBe(400)
    })

    it("reads and saves the notification settings", async () => {
        await expect(notifications.settings()).resolves.toEqual(notificationSettings)
        // Quiet hours off: the field is left out, the contract has no `null` for it.
        const saved = await notifications.saveSettings({requests: true, decisions: false, spending: true})
        expect(saved).toEqual({requests: true, decisions: false, spending: true})
        await expect(notifications.settings()).resolves.toMatchObject({spending: true})
        const raw = await fetch(`${MOCK_BASE_URL}/api/v1/me/notifications`, {method: "PUT", body: "{}"})
        expect(raw.status).toBe(400)
    })
})

describe("ActivityRepositoryImpl", () => {
    it("pages the activity, newest first", async () => {
        const activity = new ActivityRepositoryImpl(http)
        const first = await activity.page()
        expect(first.items[0]?.kind).toBe("gate.decided")
        const older = await activity.page(first.next)
        expect(older.items.map((item) => item.id)).toEqual(["act_0"])
        expect(older.next).toBeUndefined()
    })
})

describe("SpendingRepositoryImpl", () => {
    it("reads the spending of the month", async () => {
        const report = await new SpendingRepositoryImpl(http).report("month")
        expect(report.totalUsd).toBe(182.4)
        expect(report.byAgent[0]?.label).toBe("Claude Code")
    })
})

describe("createMockApi", () => {
    it("lists every request when no status is asked", async () => {
        const raw = await fetch(`${MOCK_BASE_URL}/api/v1/approvals`)
        const body: unknown = await raw.json()
        expect(body).toMatchObject({items: [{id: "appr_0001"}, {id: "appr_0002"}, {id: "appr_0000"}]})
    })

    it("serves another origin when asked", async () => {
        const other = "https://other.test"
        server.resetHandlers(...createMockApi(other).handlers)
        const repository = new AccountRepositoryImpl(new HttpClientImpl({baseUrl: other, token: () => undefined}))
        await expect(repository.me()).resolves.toMatchObject({dev: {login: "rev-b"}})
    })
})
