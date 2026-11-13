// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.ActivityPage
import run.ostrom.app.domain.models.SpendPeriod
import run.ostrom.app.domain.models.SpendingReport

/** What the organization's agents cost (`budgets.v1`). Failures are [run.ostrom.app.domain.models.AppException]s. */
interface SpendingRepository {
    suspend fun report(accountId: String, period: SpendPeriod): SpendingReport
}

/** The organization's activity, newest first, keyset-paged. Failures are [run.ostrom.app.domain.models.AppException]s. */
interface ActivityRepository {
    suspend fun page(accountId: String, before: String? = null): ActivityPage
}
