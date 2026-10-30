import {http, HttpResponse, type HttpHandler} from "msw"
import type {z} from "zod"
import {TokenExchangeRequestSchema} from "@/api/contract/account"
import {DeviceRegistrationSchema, NotificationSettingsSchema} from "@/api/contract/notifications"
import {type AskSchema, DecideRequestSchema} from "@/api/contract/yes"
import * as fixtures from "./fixtures"

type AskInput = z.input<typeof AskSchema>

function refusal(status: number, type: string, message: string): Response {
    return HttpResponse.json({error: {type, message}}, {status})
}

/**
 * An in-memory Ostrom server for tests and for running the app without a server.
 * Each call gives a fresh state, so a decision in one test never leaks into another.
 */
export function createMockApi(baseUrl: string = fixtures.MOCK_BASE_URL): {handlers: HttpHandler[]} {
    const asks = new Map<string, AskInput>(
        [fixtures.pendingAsk, fixtures.selfAsk, fixtures.decidedAsk].map((ask) => [ask.id, ask]),
    )
    let settings = fixtures.notificationSettings
    const url = (path: string): string => `${baseUrl}${path}`

    const handlers = [
        http.get(url("/api/me"), () => HttpResponse.json(fixtures.me)),
        http.post(url("/api/auth/mobile/token"), async ({request}) => {
            const body = TokenExchangeRequestSchema.safeParse(await request.json())
            if (!body.success || body.data.code !== "good-code") return refusal(400, "invalid_code", "Unknown code")
            return HttpResponse.json({token: "ost_mock_token", user: {login: fixtures.me.login}})
        }),
        http.get(url("/api/yes/queue"), () => {
            const all = [...asks.values()]
            return HttpResponse.json({
                operator: fixtures.me.login,
                pending: all.filter((ask) => ask.status === "pending"),
                recent: all.filter((ask) => ask.status !== "pending"),
            })
        }),
        http.get<{id: string}>(url("/api/yes/:id"), ({params}) => {
            const ask = asks.get(params.id)
            return ask === undefined ? refusal(404, "ask_not_found", "No such request") : HttpResponse.json(ask)
        }),
        http.post<{id: string}>(url("/api/yes/:id/decide"), async ({params, request}) => {
            const ask = asks.get(params.id)
            if (ask === undefined) return refusal(404, "ask_not_found", "No such request")
            const body = DecideRequestSchema.safeParse(await request.json())
            if (!body.success) return refusal(400, "invalid_request", "result and shownDigest are required")
            if (body.data.shownDigest !== ask.shownDigest) {
                return refusal(409, "shown_digest_mismatch", "The request changed since it was shown")
            }
            if (ask.status !== "pending") return refusal(409, "already_decided", "Already decided")
            if (body.data.result === "approved" && ask.you?.canApprove === false) {
                return refusal(403, "self_approval_refused", "You asked for this action: another approver must say yes")
            }
            const decided: AskInput = {
                ...ask,
                status: body.data.result,
                decidedBy: fixtures.me.login,
                decidedAt: new Date().toISOString(),
            }
            asks.set(ask.id, decided)
            return HttpResponse.json(decided)
        }),
        http.get(url("/api/receipts/pubkey"), () => HttpResponse.json(fixtures.receiptKey)),
        http.get(url("/api/receipts"), () => HttpResponse.json(fixtures.receipts)),
        http.get<{id: string}>(url("/api/receipts/:id"), ({params}) => {
            const receipt = fixtures.receipts.find(
                (candidate) => typeof candidate === "object" && candidate !== null && "id" in candidate && candidate.id === params.id,
            )
            return receipt === undefined ? refusal(404, "receipt_not_found", "No such receipt") : HttpResponse.json(receipt)
        }),
        http.get(url("/api/activity"), ({request}) => {
            const before = new URL(request.url).searchParams.get("before")
            return HttpResponse.json(before === null ? fixtures.activity : fixtures.olderActivity)
        }),
        http.get(url("/api/spending"), () => HttpResponse.json(fixtures.spending)),
        http.post(url("/api/devices"), async ({request}) => {
            const body = DeviceRegistrationSchema.safeParse(await request.json())
            return body.success
                ? HttpResponse.json({id: "dev_1"})
                : refusal(400, "invalid_request", "pushToken, provider, platform and locale are required")
        }),
        http.delete(url("/api/devices/:id"), () => new HttpResponse(null, {status: 204})),
        http.get(url("/api/me/notifications"), () => HttpResponse.json(settings)),
        http.put(url("/api/me/notifications"), async ({request}) => {
            const body = NotificationSettingsSchema.safeParse(await request.json())
            if (!body.success) return refusal(400, "invalid_request", "Invalid settings")
            settings = body.data
            return HttpResponse.json(settings)
        }),
    ]
    return {handlers}
}
