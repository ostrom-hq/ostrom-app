import type {z} from "zod"
import type {MeSchema} from "@/api/contract/account"
import type {ActivityPageSchema} from "@/api/contract/activity"
import type {NotificationSettingsSchema} from "@/api/contract/notifications"
import type {SpendReportSchema} from "@/api/contract/spending"
import type {AskSchema} from "@/api/contract/yes"
import receiptVectors from "@/domain/receipts/__fixtures__/receipts.json"

/** Example data shaped like the server's answers. Names are neutral (`acme`, `dev-a`, `rev-b`), as in the vectors. */
export const MOCK_BASE_URL = "https://ostrom.test"

export const me: z.input<typeof MeSchema> = {
    login: "rev-b",
    name: "Reviewer B",
    avatarUrl: null,
    org: {login: "acme", name: "Acme"},
}

export const pendingAsk: z.input<typeof AskSchema> = {
    id: "appr_0001",
    status: "pending",
    title: "Merge PR #6 into main",
    requestedAt: "2026-10-30T08:00:00.000Z",
    who: {dev: "dev-a", agent: "claude-code", model: "sonnet", session: "sess-simple"},
    what: {action: "Merge to main", command: "gh pr merge 6 --squash", repo: "acme/acme-api", harness: "0.4.0"},
    gate: {label: "Production merge", workflow: "Ship", workflowId: "wf-ship", approvers: ["rev-b"]},
    rules: [{id: "r-commit-style", statement: "Commits follow Conventional Commits", strength: "must"}],
    diff: "diff --git a/src/api.ts b/src/api.ts\n+export const ready = true\n",
    evidence: [
        {label: "Repository", value: "acme/acme-api", url: "https://github.com/acme/acme-api"},
        {label: "Pull request", value: "#6", url: "https://github.com/acme/acme-api/pull/6"},
    ],
    commits: [
        {sha: "c60f859a1b2c3d4e5f60718293a4b5c6d7e8f901", title: "feat: expose readiness", author: "dev-a"},
        {sha: "b0ff918a1b2c3d4e5f60718293a4b5c6d7e8f902", title: "test: cover readiness", author: "dev-a"},
    ],
    checks: [
        {name: "build", state: "success", url: "https://github.com/acme/acme-api/actions/runs/1"},
        {name: "tests", state: "success"},
        {name: "lint", state: "pending"},
    ],
    shownDigest: "5c1b3f4a9d2e7c8b6a5f4e3d2c1b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b",
    askedBy: ["dev-a"],
    askedVia: ["hook", "ci"],
    pr: {number: "6", sha: "c60f859"},
    you: {login: "rev-b", self: false, canApprove: true},
}

export const selfAsk: z.input<typeof AskSchema> = {
    ...pendingAsk,
    id: "appr_0002",
    title: "Push to main on acme-web",
    what: {action: "Push to main", repo: "acme/acme-web"},
    shownDigest: "0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b5c1b3f4a9d2e7c8b6a5f4e3d2c1b",
    askedBy: ["rev-b"],
    you: {
        login: "rev-b",
        self: true,
        canApprove: false,
        reason: "rev-b asked for this action and cannot approve it: dev-a must say yes",
    },
}

export const decidedAsk: z.input<typeof AskSchema> = {
    ...pendingAsk,
    id: "appr_0000",
    status: "approved",
    title: "Push to main on acme-api",
    decidedBy: "rev-b",
    decidedAt: "2026-10-30T09:00:00.000Z",
    receipt: {
        id: "rcpt_gate",
        kind: "gate",
        createdAt: "2026-10-30T09:00:00.000Z",
        valid: true,
        digest: "e619010d5fa8d39e45088e29dceb6df4719e3c28537125729291b8d8d2ee48bd",
        by: "rev-b",
        result: "approved",
    },
    you: {login: "rev-b", self: false, canApprove: false},
}

/** Receipts signed by the public test key of the golden vectors: they verify on the phone. */
export const receipts: readonly unknown[] = receiptVectors.receipts.map((vector) => vector.receipt)
export const receiptKey = {kid: receiptVectors.key.kid, alg: "ed25519", publicKeyPem: receiptVectors.key.publicKeySpkiPem}

export const activity: z.input<typeof ActivityPageSchema> = {
    items: [
        {
            id: "act_3",
            at: "2026-10-30T09:00:00.000Z",
            kind: "gate.decided",
            actor: {kind: "person", name: "rev-b"},
            summary: "rev-b approved the merge of PR #6",
            repo: "acme/acme-api",
            askId: "appr_0000",
        },
        {
            id: "act_2",
            at: "2026-10-30T08:00:00.000Z",
            kind: "gate.requested",
            actor: {kind: "agent", name: "claude-code"},
            summary: "claude-code asks to merge PR #6",
            repo: "acme/acme-api",
            askId: "appr_0001",
        },
        {
            id: "act_1",
            at: "2026-10-30T07:40:00.000Z",
            kind: "session.ended",
            actor: {kind: "agent", name: "claude-code"},
            summary: "Session ended after 7 turns",
            repo: "acme/acme-api",
            costUsd: 0.128,
        },
    ],
    nextCursor: "act_1",
}

export const olderActivity: z.input<typeof ActivityPageSchema> = {
    items: [
        {
            id: "act_0",
            at: "2026-10-29T18:00:00.000Z",
            kind: "session.started",
            actor: {kind: "agent", name: "codex"},
            summary: "Session started",
            repo: "acme/acme-web",
        },
    ],
    nextCursor: null,
}

export const spending: z.input<typeof SpendReportSchema> = {
    period: "month",
    from: "2026-10-01T00:00:00.000Z",
    to: "2026-10-31T23:59:59.999Z",
    totalUsd: 182.4,
    budgetUsd: 250,
    byAgent: [
        {key: "claude-code", label: "Claude Code", usd: 141.2, sessions: 212},
        {key: "codex", label: "Codex", usd: 41.2, sessions: 60},
    ],
    byRepo: [
        {key: "acme/acme-api", label: "acme-api", usd: 120.4, sessions: 180},
        {key: "acme/acme-web", label: "acme-web", usd: 62, sessions: 92},
    ],
    byPerson: [
        {key: "dev-a", label: "dev-a", usd: 150.1, sessions: 230},
        {key: "rev-b", label: "rev-b", usd: 32.3, sessions: 42},
    ],
    daily: [
        {date: "2026-10-28", usd: 12.5},
        {date: "2026-10-29", usd: 18.2},
        {date: "2026-10-30", usd: 6.1},
    ],
}

export const notificationSettings: z.input<typeof NotificationSettingsSchema> = {
    requests: true,
    decisions: true,
    spending: false,
    quietHours: {start: "22:00", end: "07:00", timeZone: "Europe/Paris"},
}
