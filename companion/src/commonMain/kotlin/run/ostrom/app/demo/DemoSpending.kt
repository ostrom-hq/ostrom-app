// SPDX-License-Identifier: Apache-2.0
// The demo of TeamCode's companion (Apache-2.0), with Ostrom's spending and activity; see NOTICE.
package run.ostrom.app.demo

import run.ostrom.app.domain.models.ActivityItem
import run.ostrom.app.domain.models.ActivityPage
import run.ostrom.app.domain.models.ExceededBudget
import run.ostrom.app.domain.models.SpendLine
import run.ostrom.app.domain.models.SpendPeriod
import run.ostrom.app.domain.models.SpendingReport
import run.ostrom.app.domain.repositories.ActivityRepository
import run.ostrom.app.domain.repositories.SpendingRepository

private const val MINUTE = 60_000L

/** What the demo organization's agents cost: a month close to its budget, and Ada past her own daily one. */
internal fun demoSpending(period: SpendPeriod): SpendingReport {
    val week = period == SpendPeriod.WEEK
    fun scaled(usd: Double) = if (week) usd / 4 else usd
    return SpendingReport(
        period = period,
        totalUsd = scaled(412.80),
        budgetUsd = scaled(500.0),
        byAgent = listOf(SpendLine("claude-code", "Claude Code", scaled(301.15), if (week) 21 else 84), SpendLine("codex", "Codex", scaled(111.65), if (week) 9 else 37)),
        byRepo = listOf(
            SpendLine("ae/billing", "analytical-engines/billing", scaled(198.40), if (week) 12 else 51),
            SpendLine("ae/web", "analytical-engines/web", scaled(156.25), if (week) 11 else 44),
            SpendLine("ae/docs", "analytical-engines/docs", scaled(58.15), if (week) 7 else 26),
        ),
        byPerson = listOf(SpendLine("m-ada", "ada", scaled(240.30), if (week) 17 else 69), SpendLine("m-charles", "charles", scaled(172.50), if (week) 13 else 52)),
        exceeded = listOf(ExceededBudget("member", "m-ada", null, "day", 25.0, hardStop = false)),
    )
}

/** The demo organization's activity, newest first, relative to [now]. */
internal fun demoActivity(now: Long): List<ActivityItem> = listOf(
    ActivityItem("act-1", now - 3 * MINUTE, "gate.requested", "agent", "Claude Code", "Asked to push 3 commits to main", "analytical-engines/billing", approvalId = "apr-demo-1"),
    ActivityItem("act-2", now - 18 * MINUTE, "check.passed", "agent", "Codex", "CI passed on the cache pull request", "analytical-engines/web"),
    ActivityItem("act-3", now - 47 * MINUTE, "session.ended", "agent", "Claude Code", "Session ended: migrate billing to the new API", "analytical-engines/billing", costUsd = 4.12),
    ActivityItem("act-4", now - 95 * MINUTE, "gate.decided", "person", "charles", "Approved the deploy of the docs site", "analytical-engines/docs"),
    ActivityItem("act-5", now - 6 * 60 * MINUTE, "workflow.changed", "person", "ada", "Changed the release workflow", null),
)

class DemoSpendingRepository(private val demo: DemoMode) : SpendingRepository {
    override suspend fun report(accountId: String, period: SpendPeriod): SpendingReport { demo.store(); return demoSpending(period) }
}

class DemoActivityRepository(private val demo: DemoMode) : ActivityRepository {
    override suspend fun page(accountId: String, before: String?): ActivityPage =
        ActivityPage(if (before == null) demoActivity(demo.store().startedAt) else emptyList(), null)
}

/** The demo account's spending from memory, every other account's from its server. */
class DemoAwareSpendingRepository(private val real: SpendingRepository, private val demo: SpendingRepository) : SpendingRepository {
    override suspend fun report(accountId: String, period: SpendPeriod) = (if (isDemo(accountId)) demo else real).report(accountId, period)
}

/** The demo account's activity from memory, every other account's from its server. */
class DemoAwareActivityRepository(private val real: ActivityRepository, private val demo: ActivityRepository) : ActivityRepository {
    override suspend fun page(accountId: String, before: String?) = (if (isDemo(accountId)) demo else real).page(accountId, before)
}
