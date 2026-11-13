// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.domain.usecases.spending

import run.ostrom.app.domain.models.ActivityPage
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.SpendPeriod
import run.ostrom.app.domain.models.SpendingReport
import run.ostrom.app.domain.repositories.ActivityRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.SpendingRepository
import run.ostrom.app.domain.repositories.has
import run.ostrom.app.domain.usecases.auth.AccountGuard

/** The spending of [period]; [AppException.Unsupported] from a server without budgets (the tab is hidden then). */
class LoadSpendingUseCase(private val spending: SpendingRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, period: SpendPeriod): SpendingReport = guard(accountId) {
        if (!features.has(accountId, Features.BUDGETS)) throw AppException.Unsupported(Features.BUDGETS)
        spending.report(accountId, period)
    }
}

/** A page of the organization's activity, the first one when [before] is null. Every server has it. */
class LoadActivityUseCase(private val activity: ActivityRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, before: String? = null): ActivityPage = guard(accountId) { activity.page(accountId, before) }
}
