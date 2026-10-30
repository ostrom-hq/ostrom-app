import {http, HttpResponse, type HttpHandler} from "msw"
import type {z} from "zod"
import type {ApprovalSchema, ApprovalViewSchema} from "@/api/contract/approvals"
import {
    zAnswerApprovalBody,
    zMobileTokenBody,
    zRegisterPushDeviceBody,
    zSetNotificationPreferencesBody,
} from "@/api/gen/zod.gen"
import * as fixtures from "./fixtures"

type ApprovalInput = z.input<typeof ApprovalSchema>

/** The error body of the contract (`ApiError`); `type` is the code of the server's `ErrorKind`. */
function refusal(status: number, type: string, message: string): Response {
    return HttpResponse.json({error: {type, message}}, {status})
}

/**
 * An in-memory Ostrom server on the `/api/v1` routes of the contract, for tests and for running the app without a
 * server. Each call gives a fresh state, so an answer in one test never leaks into another.
 * The caller is `rev-b`; their yes on a request they asked for is refused, as the server does when someone else can
 * approve, and their no is always taken.
 */
export function createMockApi(baseUrl: string = fixtures.MOCK_BASE_URL): {handlers: HttpHandler[]} {
    const approvals = new Map<string, ApprovalInput>(
        [fixtures.pendingApproval, fixtures.selfApproval, fixtures.deployApproval, fixtures.decidedApproval].map((approval) => [
            approval.id,
            approval,
        ]),
    )
    let settings = fixtures.notificationSettings
    const url = (path: string): string => `${baseUrl}${path}`
    const caller = fixtures.me.dev.login
    /** A request as the console and the app show it: the caller's own request is theirs, and not theirs to approve. */
    const view = (approval: ApprovalInput): z.input<typeof ApprovalViewSchema> => {
        const self = approval.askedBy?.includes(caller) === true
        return {
            approval,
            who: {agent: "claude-code", model: "claude-opus-5-5"},
            what: {command: "gh pr merge 6 --squash"},
            gate: {workflowName: "Release", label: "Merge to main"},
            you: self
                ? {login: caller, self, canApprove: false, reason: "You asked for this action: another approver must say yes"}
                : {login: caller, self, canApprove: true},
        }
    }

    const handlers = [
        http.get(url("/api/v1/auth/me"), () => HttpResponse.json(fixtures.me)),
        http.post(url("/api/v1/auth/mobile/token"), async ({request}) => {
            const body = zMobileTokenBody.safeParse(await request.json())
            if (!body.success || body.data.code !== "good-code") return refusal(400, "invalid_request", "Unknown code")
            return HttpResponse.json({token: "ost_mock_token", user: {login: caller}})
        }),
        http.post(url("/api/v1/auth/logout"), () => new HttpResponse(null, {status: 204})),
        http.get(url("/api/v1/approvals/queue"), () => {
            const all = [...approvals.values()]
            return HttpResponse.json({
                operator: caller,
                you: {login: caller, via: "app"},
                pending: all.filter((approval) => approval.status === "pending").map(view),
                recent: all.filter((approval) => approval.status !== "pending").map(view),
            })
        }),
        http.get<{id: string}>(url("/api/v1/approvals/:id/view"), ({params}) => {
            const approval = approvals.get(params.id)
            return approval === undefined ? refusal(404, "not_found", "No such request") : HttpResponse.json(view(approval))
        }),
        http.post<{id: string}>(url("/api/v1/approvals/:id/answer"), async ({params, request}) => {
            const approval = approvals.get(params.id)
            if (approval === undefined) return refusal(404, "not_found", "No such request")
            const body = zAnswerApprovalBody.safeParse(await request.json())
            if (!body.success) return refusal(400, "invalid_request", "answer and shownDigest are required")
            if (body.data.shownDigest !== approval.shownDigest) {
                return refusal(409, "shown_digest_mismatch", "The request changed since it was shown")
            }
            if (approval.status !== "pending") return refusal(409, "conflict", "This gate was already answered.")
            if (body.data.answer === "approve" && approval.askedBy?.includes(caller) === true) {
                return refusal(403, "forbidden", "You asked for this action: another approver must say yes")
            }
            const answered: ApprovalInput = {
                ...approval,
                status: body.data.answer === "approve" ? "approved" : "rejected",
                decidedBy: caller,
                decidedAt: new Date().toISOString(),
            }
            approvals.set(approval.id, answered)
            return HttpResponse.json(view(answered))
        }),
        http.get(url("/api/v1/receipts/keys"), () => HttpResponse.json({keys: [fixtures.receiptKey]})),
        http.get(url("/api/v1/receipts"), () => HttpResponse.json({items: fixtures.receipts})),
        http.get<{id: string}>(url("/api/v1/receipts/:id"), ({params}) => {
            const receipt = fixtures.receipts.find(
                (candidate) => typeof candidate === "object" && candidate !== null && "id" in candidate && candidate.id === params.id,
            )
            return receipt === undefined ? refusal(404, "not_found", "No such receipt") : HttpResponse.json(receipt)
        }),
        http.get(url("/api/v1/activity"), ({request}) => {
            const before = new URL(request.url).searchParams.get("before")
            return HttpResponse.json(before === null ? fixtures.activity : fixtures.olderActivity)
        }),
        http.get(url("/api/v1/spending"), () => HttpResponse.json(fixtures.spending)),
        http.post(url("/api/v1/push-devices"), async ({request}) => {
            const body = zRegisterPushDeviceBody.safeParse(await request.json())
            return body.success
                ? HttpResponse.json(
                      {id: "dev_1", provider: body.data.provider, platform: body.data.platform, createdAt: "2026-10-30T08:00:00.000Z"},
                      {status: 201},
                  )
                : refusal(400, "invalid_request", "pushToken, provider and platform are required")
        }),
        http.delete(url("/api/v1/push-devices/:id"), () => new HttpResponse(null, {status: 204})),
        http.get(url("/api/v1/me/notifications"), () => HttpResponse.json(settings)),
        http.put(url("/api/v1/me/notifications"), async ({request}) => {
            const body = zSetNotificationPreferencesBody.safeParse(await request.json())
            if (!body.success) return refusal(400, "invalid_request", "Invalid settings")
            settings = body.data
            return HttpResponse.json(settings)
        }),
    ]
    return {handlers}
}
