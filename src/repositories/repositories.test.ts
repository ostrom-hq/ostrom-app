import {http as httpMock, HttpResponse} from "msw"
import {setupServer} from "msw/node"
import {HttpClientImpl} from "@/api/http/HttpClientImpl"
import {ApiError} from "@/api/http/ApiError"
import {MOCK_BASE_URL, notificationSettings, pendingAsk, receipts, selfAsk} from "@/api/mocks/fixtures"
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

    it("reads what waits for me and what was decided", async () => {
        const queue = await approvals.queue()
        expect(queue.operator).toBe("rev-b")
        expect(queue.pending.map((ask) => ask.id)).toEqual(["appr_0001", "appr_0002"])
        expect(queue.recent.map((ask) => ask.status)).toEqual(["approved"])
    })

    it("reads one request with its evidence, commits and checks", async () => {
        const ask = await approvals.ask("appr_0001")
        expect(ask.commits).toHaveLength(2)
        expect(ask.checks.map((check) => check.state)).toEqual(["success", "success", "pending"])
        expect(await refusal(approvals.ask("nope"))).toBe("ask_not_found")
    })

    it("approves a request bound to what was shown", async () => {
        const decided = await approvals.decide("appr_0001", "approved", pendingAsk.shownDigest)
        expect(decided).toMatchObject({status: "approved", decidedBy: "rev-b"})
        expect(await refusal(approvals.decide("appr_0001", "rejected", pendingAsk.shownDigest))).toBe("already_decided")
    })

    it("refuses a yes on a request that changed since it was shown", async () => {
        expect(await refusal(approvals.decide("appr_0001", "approved", "stale"))).toBe("shown_digest_mismatch")
    })

    it("refuses my own yes when someone else can approve, but takes my no", async () => {
        expect(await refusal(approvals.decide("appr_0002", "approved", selfAsk.shownDigest))).toBe("self_approval_refused")
        await expect(approvals.decide("appr_0002", "rejected", selfAsk.shownDigest)).resolves.toMatchObject({
            status: "rejected",
        })
    })

    it("refuses an unknown request or a malformed decision", async () => {
        expect(await refusal(approvals.decide("nope", "approved", "x"))).toBe("ask_not_found")
        const raw = await fetch(`${MOCK_BASE_URL}/api/yes/appr_0001/decide`, {method: "POST", body: "{}"})
        expect(raw.status).toBe(400)
    })
})

describe("ReceiptsRepositoryImpl", () => {
    const repository = new ReceiptsRepositoryImpl(http)

    it("keeps each receipt raw next to its typed view", async () => {
        const list = await repository.list()
        expect(list).toHaveLength(receipts.length)
        const [first] = list
        expect(first?.raw).toEqual(receipts[0])
        expect(first?.readable === true ? first.view.signature : "").toMatch(/^ed25519;kid=/)
    })

    it("keeps an unreadable receipt in the list instead of failing it", async () => {
        server.use(httpMock.get(`${MOCK_BASE_URL}/api/receipts`, () => HttpResponse.json([receipts[0], {id: 1}])))
        const list = await repository.list()
        expect(list.map((item) => item.readable)).toEqual([true, false])
        expect(list[1]?.readable === false ? list[1].detail : "").toContain("id")
    })

    it("reads one receipt and the instance key", async () => {
        const [first] = await repository.list()
        if (first?.readable !== true) throw new Error("no readable receipt")
        await expect(repository.get(first.view.id)).resolves.toEqual(first)
        expect(await refusal(repository.get("nope"))).toBe("receipt_not_found")
        await expect(repository.publicKey()).resolves.toMatchObject({alg: "ed25519", kid: "b918e282a924dda0"})
    })
})

describe("AccountRepositoryImpl", () => {
    const account = new AccountRepositoryImpl(http)

    it("reads who I am", async () => {
        await expect(account.me()).resolves.toMatchObject({login: "rev-b", org: {login: "acme"}})
    })

    it("trades the sign-in code for a token, and refuses a wrong code", async () => {
        const request = {codeVerifier: "v", redirectUri: "ostrom://auth/callback"}
        await expect(account.exchangeCode({...request, code: "good-code"})).resolves.toMatchObject({token: "ost_mock_token"})
        expect(await refusal(account.exchangeCode({...request, code: "bad"}))).toBe("invalid_code")
        const raw = await fetch(`${MOCK_BASE_URL}/api/auth/mobile/token`, {method: "POST", body: "{}"})
        expect(raw.status).toBe(400)
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
        expect(device).toEqual({id: "dev_1"})
        await expect(notifications.unregisterDevice(device.id)).resolves.toBeUndefined()
        const raw = await fetch(`${MOCK_BASE_URL}/api/devices`, {method: "POST", body: "{}"})
        expect(raw.status).toBe(400)
    })

    it("reads and saves the notification settings", async () => {
        await expect(notifications.settings()).resolves.toEqual(notificationSettings)
        const saved = await notifications.saveSettings({...notificationSettings, spending: true, quietHours: null})
        expect(saved).toMatchObject({spending: true, quietHours: null})
        await expect(notifications.settings()).resolves.toMatchObject({spending: true})
        const raw = await fetch(`${MOCK_BASE_URL}/api/me/notifications`, {method: "PUT", body: "{}"})
        expect(raw.status).toBe(400)
    })
})

describe("ActivityRepositoryImpl", () => {
    it("pages the activity, newest first", async () => {
        const activity = new ActivityRepositoryImpl(http)
        const first = await activity.page()
        expect(first.items[0]?.kind).toBe("gate.decided")
        const older = await activity.page(first.nextCursor ?? undefined)
        expect(older.items.map((item) => item.id)).toEqual(["act_0"])
        expect(older.nextCursor).toBeNull()
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
    it("serves another origin when asked", async () => {
        const other = "https://other.test"
        server.resetHandlers(...createMockApi(other).handlers)
        const repository = new AccountRepositoryImpl(new HttpClientImpl({baseUrl: other, token: () => undefined}))
        await expect(repository.me()).resolves.toMatchObject({login: "rev-b"})
    })
})
