// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.ActivityItem
import run.ostrom.app.domain.models.ActivityPage
import run.ostrom.app.domain.models.ExceededBudget
import run.ostrom.app.domain.models.SpendLine
import run.ostrom.app.domain.models.SpendPeriod
import run.ostrom.app.domain.models.SpendingReport
import run.ostrom.app.domain.repositories.ActivityRepository
import run.ostrom.app.domain.repositories.SpendingRepository
import run.ostrom.core.api.models.SpendingLine

/** The spending over the typed client (`GET /api/v1/spending`). */
class RemoteSpendingRepository(private val calls: AccountCalls) : SpendingRepository {
    override suspend fun report(accountId: String, period: SpendPeriod): SpendingReport = calls.withAccount(accountId) { client ->
        val report = client.spending.report(period.wire)
        SpendingReport(
            period = period,
            totalUsd = report.totalUsd,
            budgetUsd = report.budgetUsd,
            byAgent = report.byAgent.map { it.domain() },
            byRepo = report.byRepo.map { it.domain() },
            byPerson = report.byPerson.map { it.domain() },
            exceeded = report.exceeded.map { ExceededBudget(it.scope, it.subject, it.label, it.period, it.usd, it.hardStop) },
        )
    }

    private fun SpendingLine.domain() = SpendLine(key, label, usd, sessions)
}

/** The activity over the typed client (`GET /api/v1/activity`). */
class RemoteActivityRepository(private val calls: AccountCalls) : ActivityRepository {
    override suspend fun page(accountId: String, before: String?): ActivityPage = calls.withAccount(accountId) { client ->
        val page = client.activity.page(before = before)
        ActivityPage(
            page.items.map { item ->
                ActivityItem(item.id, millisOf(item.at), item.kind, item.actor.kind, item.actor.name, item.summary, item.repo, item.costUsd, item.approvalId)
            },
            page.next,
        )
    }
}
