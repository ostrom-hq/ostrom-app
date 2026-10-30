import type {z} from "zod"
import type {ActivityPageSchema} from "@/api/contract/activity"
import type {MeSchema} from "@/api/contract/account"
import type {ApprovalSchema} from "@/api/contract/approvals"
import type {NotificationSettingsSchema} from "@/api/contract/notifications"
import type {SpendReportSchema} from "@/api/contract/spending"
import receiptVectors from "@/domain/receipts/__fixtures__/receipts.json"

/** Example data shaped like the server's answers. Names are neutral (`acme`, `dev-a`, `rev-b`), as in the vectors. */
export const MOCK_BASE_URL = "https://ostrom.test"

export const me: z.input<typeof MeSchema> = {
    dev: {login: "rev-b", verified: true, via: "github"},
    member: {id: "6f1c2a4e-8b3d-4c5e-9f70-1a2b3c4d5e6f", login: "rev-b", name: "Reviewer B", avatarUrl: "https://avatars.test/rev-b", role: "member"},
    capabilities: ["approvals.answer"],
}

export const pendingApproval: z.input<typeof ApprovalSchema> = {
    id: "appr_0001",
    title: "Merge PR #6 into main",
    repo: "acme/acme-api",
    sha: "c60f859a1b2c3d4e5f60718293a4b5c6d7e8f901",
    commits: ["c60f859a1b2c3d4e5f60718293a4b5c6d7e8f901", "b0ff918a1b2c3d4e5f60718293a4b5c6d7e8f902"],
    commitDetails: [
        {sha: "c60f859a1b2c3d4e5f60718293a4b5c6d7e8f901", title: "feat: expose readiness", author: "dev-a"},
        {sha: "b0ff918a1b2c3d4e5f60718293a4b5c6d7e8f902", title: "test: cover readiness", author: "dev-a"},
    ],
    checks: [
        {name: "build", state: "success", url: "https://github.com/acme/acme-api/actions/runs/1"},
        {name: "tests", state: "success"},
        {name: "lint", state: "pending"},
    ],
    evidence: [
        {label: "Pull request", value: "#6", url: "https://github.com/acme/acme-api/pull/6"},
        {label: "Command", value: "gh pr merge 6 --squash"},
    ],
    requestedFrom: "rev-b",
    channel: "app",
    status: "pending",
    shownDigest: "5c1b3f4a9d2e7c8b6a5f4e3d2c1b0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b",
    askedBy: ["dev-a"],
    expiresAt: "2026-10-30T18:00:00.000Z",
}

/** A request the person asked for themselves: the server takes their yes only when nobody else can give it. */
export const selfApproval: z.input<typeof ApprovalSchema> = {
    ...pendingApproval,
    id: "appr_0002",
    title: "Push to main on acme-web",
    repo: "acme/acme-web",
    shownDigest: "0a9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b5c1b3f4a9d2e7c8b6a5f4e3d2c1b",
    askedBy: ["rev-b"],
}

/** A low-risk deploy that names its action (contract 0.1.129): Approve says what it lets through, without Face ID. */
export const deployApproval: z.input<typeof ApprovalSchema> = {
    ...pendingApproval,
    id: "appr_0003",
    title: "Deploy acme-api to staging",
    shownDigest: "7e6d5c4b3a2f1e0d9c8b7a6f5e4d3c2b1a0f9e8d7c6b5a4f3e2d1c0b9a8f7e6d",
    action: {kind: "deploy", repo: "acme/acme-api", ref: "main", headSha: "c60f859a1b2c3d4e5f60718293a4b5c6d7e8f901", risk: "low"},
    diffStats: {files: 2, additions: 41, deletions: 7},
}

export const decidedApproval: z.input<typeof ApprovalSchema> = {
    ...pendingApproval,
    id: "appr_0000",
    title: "Push to main on acme-api",
    status: "approved",
    decidedBy: "rev-b",
    decidedAt: "2026-10-30T09:00:00.000Z",
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
            approvalId: "appr_0000",
        },
        {
            id: "act_2",
            at: "2026-10-30T08:00:00.000Z",
            kind: "gate.requested",
            actor: {kind: "agent", name: "claude-code"},
            summary: "claude-code asks to merge PR #6",
            repo: "acme/acme-api",
            approvalId: "appr_0001",
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
    next: "act_1",
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
