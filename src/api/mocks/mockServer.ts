// SPDX-License-Identifier: AGPL-3.0-only
import type {z} from "zod"
import type {ActivityPageSchema} from "@/api/contract/activity"
import type {ApprovalSchema, ApprovalViewSchema} from "@/api/contract/approvals"
import type {MyOrganizationsSchema} from "@/api/contract/organizations"
import {
    zAnswerApprovalBody,
    zMobileTokenBody,
    zRegisterPushDeviceBody,
    zSetNotificationPreferencesBody,
} from "@/api/gen/zod.gen"
import {pkceChallenge} from "@/services/signIn/pkce"
import * as fixtures from "./fixtures"

type ApprovalInput = z.input<typeof ApprovalSchema>

/** The unlock key the mock server gives with its token, and takes back. */
export const MOCK_UNLOCK_KEY = "mock-unlock-key"

/** A call as the mock server reads it: the method, the path under the origin, its query, and the parsed JSON body. */
export interface MockRequest {
    readonly method: string
    readonly path: string
    readonly query: Readonly<Record<string, string>>
    readonly body: unknown
    /** The `Ostrom-Org` header, when the call has one. */
    readonly org?: string | undefined
}

export interface MockServerOptions {
    /** The caller's active memberships: one by default (`acme`), so the header may be left out. */
    readonly memberships?: readonly string[]
    /**
     * Lists this long on top of the fixtures, for the performance pass (`scripts/perf-measure.sh`): waiting requests,
     * activity rows and receipts (the signed fixtures, again and again, so that each one is verified for real).
     */
    readonly many?: number
}

/** The answer: a status and a JSON body, or none (204). */
export interface MockResponse {
    readonly status: number
    readonly body?: unknown
}

export interface MockServer {
    handle(request: MockRequest): MockResponse
}

/** The error body of the contract (`ApiError`); `type` is the code of the server's `ErrorKind`. */
function refusal(status: number, type: string, message: string): MockResponse {
    return {status, body: {error: {type, message}}}
}

const ok = (body: unknown): MockResponse => ({status: 200, body})

/** A path segment decoded, or nothing when it is not valid percent-encoding. */
function idOf(segment: string): string | undefined {
    try {
        return decodeURIComponent(segment)
    } catch {
        return undefined
    }
}

interface Route {
    readonly method: string
    readonly path: RegExp
    /** It acts in the caller's organization: the contract gives it the `Ostrom-Org` header. */
    readonly inOrg: boolean
    readonly respond: (request: MockRequest, id: string) => MockResponse
}

/**
 * The routes of the app that act in the caller's organization (contract 0.1.142); the others take no header. `me` and
 * `me/organizations` never need it (core#31, server#64): they are how the app learns which organization to name.
 */
const IN_ORG = /^\/api\/v1\/(push-devices|activity|spending|approvals|receipts(?!\/keys))(?=\/|$)/

const pad = (index: number): string => String(index).padStart(4, "0")

/** Waiting requests for the performance pass, each one a merge of its own pull request. */
function manyApprovals(count: number): ApprovalInput[] {
    return Array.from({length: count}, (_, index) => ({
        ...fixtures.pendingApproval,
        id: `appr_many_${pad(index)}`,
        title: `Merge PR #${String(100 + index)} into main`,
        repo: index % 2 === 0 ? "acme/acme-api" : "acme/acme-web",
    }))
}

/** Activity rows for the performance pass, an hour apart, without a next page. */
function manyActivity(count: number): z.input<typeof ActivityPageSchema>["items"] {
    return Array.from({length: count}, (_, index) => ({
        id: `act_many_${pad(index)}`,
        at: new Date(Date.UTC(2026, 9, 29, 0) - index * 3_600_000).toISOString(),
        kind: "gate.requested",
        actor: {kind: "agent", name: "claude-code"},
        summary: `claude-code asks to merge PR #${String(100 + index)}`,
        repo: "acme/acme-api",
        approvalId: `appr_many_${pad(index)}`,
    }))
}

/** A route of the contract; `:id` is one path segment. */
function route(method: string, pattern: string, respond: Route["respond"]): Route {
    return {method, path: new RegExp(`^${pattern.replace(":id", "([^/]+)")}$`), inOrg: IN_ORG.test(pattern), respond}
}

/**
 * An in-memory Ostrom server on the `/api/v1` routes of the contract, for the tests (through msw) and for the E2E
 * build (through an in-memory `fetch`). Each call gives a fresh state, so an answer never leaks into another test.
 * The caller is `rev-b`. Their yes on a request they asked for is refused (`self_approval_refused`), as the server does
 * when someone else can approve, and their no is always taken. A yes on the production deploy needs a fresh sign-in
 * (`step_up_required`). A route of the caller's organization reads `Ostrom-Org` as the server does: without it, the
 * one membership, or 409 `org_required` when there are several; an organization the caller is not in, 404.
 */
export function createMockServer({memberships = [fixtures.acme.id], many = 0}: MockServerOptions = {}): MockServer {
    const approvals = new Map<string, ApprovalInput>(
        [
            fixtures.pendingApproval,
            fixtures.selfApproval,
            fixtures.deployApproval,
            fixtures.stepUpApproval,
            fixtures.decidedApproval,
            ...manyApprovals(many),
        ].map((approval) => [approval.id, approval]),
    )
    const receipts = [...fixtures.receipts, ...Array.from({length: many}, (_, index) => fixtures.receipts[index % fixtures.receipts.length])]
    const activity = many === 0 ? fixtures.activity : {items: [...fixtures.activity.items, ...manyActivity(many)]}
    let settings = fixtures.notificationSettings
    const caller = fixtures.me.dev.login

    /** Whether the caller asked for it: then their yes is not taken, as the server does when someone else can approve. */
    const isSelf = (approval: ApprovalInput): boolean => approval.askedBy?.includes(caller) === true

    /** A request as the console and the app show it: the caller's own request is theirs, and not theirs to approve. */
    const view = (approval: ApprovalInput): z.input<typeof ApprovalViewSchema> => {
        const self = isSelf(approval)
        const deploy = approval.action?.kind === "deploy"
        return {
            approval,
            who: {agent: "claude-code", model: "claude-opus-5-5"},
            what: {command: deploy ? "ostrom deploy --env production" : "gh pr merge 6 --squash"},
            gate: {workflowName: "Release", label: deploy ? "Deploy to production" : "Merge to main"},
            you: self
                ? {login: caller, self, canApprove: false, reason: "You asked for this action: another approver must say yes"}
                : {login: caller, self, canApprove: true},
        }
    }

    const answer = (id: string, raw: unknown): MockResponse => {
        const approval = approvals.get(id)
        if (approval === undefined) return refusal(404, "not_found", "No such request")
        const body = zAnswerApprovalBody.safeParse(raw)
        if (!body.success) return refusal(400, "invalid_request", "answer and shownDigest are required")
        if (body.data.shownDigest !== approval.shownDigest) {
            return refusal(409, "shown_digest_mismatch", "The request changed since it was shown")
        }
        if (approval.status !== "pending") return refusal(409, "conflict", "This gate was already answered.")
        // The server's order: the shown digest and the status first, then who may say yes, then a fresh sign-in.
        if (body.data.answer === "approve" && isSelf(approval)) {
            return refusal(403, "self_approval_refused", "You asked for this action: another approver must say yes")
        }
        if (body.data.answer === "approve" && approval.id === fixtures.stepUpApproval.id) {
            return refusal(403, "step_up_required", "Sign in again, then answer")
        }
        const answered: ApprovalInput = {
            ...approval,
            status: body.data.answer === "approve" ? "approved" : "rejected",
            decidedBy: caller,
            decidedAt: new Date().toISOString(),
        }
        approvals.set(approval.id, answered)
        return ok(view(answered))
    }

    /** The server's `MembershipOrgResolver`, reduced to the header and the memberships. */
    const orgRefusal = (org: string | undefined): MockResponse | undefined => {
        if (memberships.length === 0) return refusal(404, "not_found", "No organization")
        if (org === undefined) {
            return memberships.length === 1
                ? undefined
                : refusal(409, "org_required", "Name the organization with the Ostrom-Org header")
        }
        return memberships.includes(org) ? undefined : refusal(404, "not_found", "No such organization")
    }

    const routes: readonly Route[] = [
        route("GET", "/api/v1/auth/me", () => ok(fixtures.me)),
        // As server#64 serves it: slug and role besides what the app reads, no picture without a GitHub organization.
        route("GET", "/api/v1/me/organizations", () =>
            ok({
                items: memberships.map((id) => {
                    const known = [fixtures.acme, fixtures.globex].find((organization) => organization.id === id)
                    const name = known?.name ?? id
                    return {id, slug: name, name, avatarUrl: known?.avatarUrl ?? "", role: "member"}
                }),
            } satisfies z.input<typeof MyOrganizationsSchema>),
        ),
        route(
            "POST",
            "/api/v1/auth/mobile/token",
            ({body}) => {
                const parsed = zMobileTokenBody.safeParse(body)
                // `good-code` for the unit tests; `pkce:<challenge>` from the E2E auth host (scripts/e2e-auth-host.py),
                // taken only with the verifier of that challenge, as the server does.
                const fromBrowser =
                    parsed.success &&
                    parsed.data.redirectUri === "ostrom://auth/callback" &&
                    parsed.data.code === `pkce:${pkceChallenge(parsed.data.codeVerifier)}`
                return parsed.success && (parsed.data.code === "good-code" || fromBrowser)
                    ? ok({token: "ost_mock_token", user: {login: caller}, unlockKey: MOCK_UNLOCK_KEY})
                    : refusal(400, "invalid_request", "Unknown code")
            },
        ),
        // server#75: the key of the exchange, presented after the phone's biometrics.
        route("POST", "/api/v1/auth/mobile/unlock", ({body}) =>
            typeof body === "object" && body !== null && "unlockKey" in body && body.unlockKey === MOCK_UNLOCK_KEY
                ? ok({authenticatedAt: new Date().toISOString()})
                : refusal(403, "forbidden", "This key does not unlock this token"),
        ),
        route("POST", "/api/v1/auth/logout", () => ({status: 204})),
        route(
            "GET",
            "/api/v1/approvals/queue",
            () => {
                const all = [...approvals.values()]
                return ok({
                    operator: caller,
                    you: {login: caller, via: "app"},
                    pending: all.filter((approval) => approval.status === "pending").map(view),
                    recent: all.filter((approval) => approval.status !== "pending").map(view),
                })
            },
        ),
        route(
            "GET",
            "/api/v1/approvals/:id/view",
            (_request, id) => {
                const approval = approvals.get(id)
                return approval === undefined ? refusal(404, "not_found", "No such request") : ok(view(approval))
            },
        ),
        route("POST", "/api/v1/approvals/:id/answer", ({body}, id) => answer(id, body)),
        route("GET", "/api/v1/receipts/keys", () => ok({keys: [fixtures.receiptKey]})),
        route("GET", "/api/v1/receipts", () => ok({items: receipts})),
        route(
            "GET",
            "/api/v1/receipts/:id",
            (_request, id) => {
                const receipt = fixtures.receipts.find(
                    (candidate) => typeof candidate === "object" && candidate !== null && "id" in candidate && candidate.id === id,
                )
                return receipt === undefined ? refusal(404, "not_found", "No such receipt") : ok(receipt)
            },
        ),
        route("GET", "/api/v1/activity", ({query}) => ok(query["before"] === undefined ? activity : fixtures.olderActivity)),
        route("GET", "/api/v1/spending", () => ok(fixtures.spending)),
        route(
            "POST",
            "/api/v1/push-devices",
            ({body}) => {
                const parsed = zRegisterPushDeviceBody.safeParse(body)
                return parsed.success
                    ? {
                          status: 201,
                          body: {id: "dev_1", provider: parsed.data.provider, platform: parsed.data.platform, createdAt: "2026-10-30T08:00:00.000Z"},
                      }
                    : refusal(400, "invalid_request", "pushToken, provider and platform are required")
            },
        ),
        route("DELETE", "/api/v1/push-devices/:id", () => ({status: 204})),
        route("GET", "/api/v1/me/notifications", () => ok(settings)),
        route(
            "PUT",
            "/api/v1/me/notifications",
            ({body}) => {
                const parsed = zSetNotificationPreferencesBody.safeParse(body)
                if (!parsed.success) return refusal(400, "invalid_request", "Invalid settings")
                settings = parsed.data
                return ok(settings)
            },
        ),
    ]

    return {
        handle(request) {
            for (const {method, path, inOrg, respond} of routes) {
                const match = method === request.method ? path.exec(request.path) : null
                if (match === null) continue
                const refused = inOrg ? orgRefusal(request.org) : undefined
                if (refused !== undefined) return refused
                // Routes without an id have no group: their id is empty, and unused.
                const id = idOf(match[1] ?? "")
                return id === undefined ? refusal(404, "not_found", "No such request") : respond(request, id)
            }
            return refusal(404, "not_found", "No such route")
        },
    }
}
