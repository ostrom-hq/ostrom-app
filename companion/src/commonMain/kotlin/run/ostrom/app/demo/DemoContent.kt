// SPDX-License-Identifier: Apache-2.0
// The demo of TeamCode's companion (Apache-2.0), with Ostrom's content; see NOTICE.
package run.ostrom.app.demo

import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.ActionKind
import run.ostrom.app.domain.models.ActionRisk
import run.ostrom.app.domain.models.Approval
import run.ostrom.app.domain.models.ApprovalAction
import run.ostrom.app.domain.models.ApprovalAsker
import run.ostrom.app.domain.models.ApprovalCheck
import run.ostrom.app.domain.models.ApprovalCommit
import run.ostrom.app.domain.models.ApprovalEvidence
import run.ostrom.app.domain.models.ApprovalGate
import run.ostrom.app.domain.models.ApprovalQueue
import run.ostrom.app.domain.models.ApprovalStatus
import run.ostrom.app.domain.models.ApprovalTarget
import run.ostrom.app.domain.models.ApprovalViewer
import run.ostrom.app.domain.models.CheckState
import run.ostrom.app.domain.models.DiffStats
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.SignInMethod

/** The built-in demo's account and content, in English. Each feature adds its sample data here. */
object DemoContent {
    const val ACCOUNT_ID = "demo"

    /** A domain that never resolves (RFC 6761): nothing of the demo can reach a network. */
    const val ORIGIN = "https://demo.ostrom.invalid"
    const val SERVER_VERSION = "demo"
    const val REPOSITORY_URL = "https://github.com/ostrom-hq/ostrom-server"

    /** The demo's high-risk push, which the demo activity's request line opens. */
    const val PUSH_APPROVAL_ID = "demo-push"

    /** What the demo server announces: the features whose demo content exists. */
    val features: Set<String> = setOf(
        Features.GATES, Features.WAITS, Features.RECEIPTS, Features.BUDGETS, Features.NOTIFICATIONS, Features.ARTIFACTS, Features.SESSIONS, Features.TODOS,
        Features.WORKFLOWS,
    )

    /**
     * The approvals inbox: a high-risk push and a low-risk tool waiting for Ada's yes, and a merge she approved. The
     * times count from [startedAt], the start of the demo.
     */
    fun approvals(startedAt: Long): ApprovalQueue {
        val minute = 60_000L
        val push = Approval(
            id = PUSH_APPROVAL_ID,
            title = "Push 2 commits to main",
            status = ApprovalStatus.PENDING,
            shownDigest = "sha256:demo-push",
            requestedFrom = "ada",
            viewer = ApprovalViewer(),
            repo = "analytical-engines/engine",
            sha = "4f9c2a17e3b8d05c61a9e2f4b7d3c08a15e6f927",
            commits = listOf(
                ApprovalCommit("4f9c2a17e3b8d05c61a9e2f4b7d3c08a15e6f927", "Carry the tens in the mill", "charles"),
                ApprovalCommit("9b1e6d4c2a7f3e8b05d19c6a4e2f7b3d8c0a5e16", "Test the carriage on 40 digits", "charles"),
            ),
            checks = listOf(ApprovalCheck("build", CheckState.SUCCESS), ApprovalCheck("tests", CheckState.SUCCESS), ApprovalCheck("lint", CheckState.PENDING)),
            evidence = listOf(ApprovalEvidence("Tests", "412 passed, 0 failed")),
            askedBy = listOf("charles"),
            expiresAt = startedAt + 55 * minute,
            action = ApprovalAction(ActionKind.PUSH, "analytical-engines/engine", "main", "4f9c2a17e3b8d05c61a9e2f4b7d3c08a15e6f927", risk = ActionRisk.HIGH),
            diffStats = DiffStats(files = 3, additions = 48, deletions = 6),
            who = ApprovalAsker(dev = "charles", agent = "Claude Code", model = "claude-opus"),
            what = ApprovalTarget(action = "push-main", command = "git push origin main"),
            gate = ApprovalGate("Release", "Push to main"),
        )
        val tool = Approval(
            id = "demo-tool",
            title = "Run the database migration on staging",
            status = ApprovalStatus.PENDING,
            shownDigest = "sha256:demo-tool",
            requestedFrom = "ada",
            viewer = ApprovalViewer(),
            repo = "analytical-engines/engine",
            askedBy = listOf("charles"),
            expiresAt = startedAt + 25 * minute,
            action = ApprovalAction(ActionKind.TOOL, "analytical-engines/engine", "staging", "9b1e6d4c2a7f3e8b05d19c6a4e2f7b3d8c0a5e16", risk = ActionRisk.LOW),
            who = ApprovalAsker(dev = "charles", agent = "Claude Code", model = "claude-sonnet"),
            what = ApprovalTarget(action = "migrate-staging", command = "./gradlew flywayMigrate -Penv=staging"),
            gate = ApprovalGate("Staging", "Database changes"),
        )
        val merged = Approval(
            id = "demo-merge",
            title = "Merge pull request #42: punched cards reader",
            status = ApprovalStatus.APPROVED,
            shownDigest = "sha256:demo-merge",
            requestedFrom = "ada",
            viewer = ApprovalViewer(),
            repo = "analytical-engines/engine",
            askedBy = listOf("charles"),
            decidedBy = "ada",
            decidedAt = startedAt - 90 * minute,
            action = ApprovalAction(ActionKind.MERGE, "analytical-engines/engine", "main", "1d7e3a9c5b2f8e4d06a1c9b7e3f5d2a8c4b0e619", risk = ActionRisk.HIGH),
            who = ApprovalAsker(dev = "charles", agent = "Claude Code"),
            gate = ApprovalGate("Release", "Merge to main"),
            note = "Thanks, ship it.",
        )
        return ApprovalQueue(pending = listOf(push, tool), recent = listOf(merged))
    }

    fun account(): Account = Account(
        id = ACCOUNT_ID,
        origin = ORIGIN,
        login = "ada",
        name = "Ada Lovelace",
        organizationId = "org-demo",
        organizationName = "Analytical Engines",
        serverVersion = SERVER_VERSION,
        method = SignInMethod.BROWSER,
    )
}

/** Whether this account is the built-in demo's. */
val Account.isDemo: Boolean get() = id == DemoContent.ACCOUNT_ID

internal fun isDemo(accountId: String): Boolean = accountId == DemoContent.ACCOUNT_ID
