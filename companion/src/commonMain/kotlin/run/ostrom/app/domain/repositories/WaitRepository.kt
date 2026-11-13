// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/WaitRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import kotlinx.serialization.json.JsonElement
import run.ostrom.app.domain.models.Wait
import run.ostrom.app.domain.models.WaitPage
import run.ostrom.app.domain.models.WaitStatus

/** What waits for the member on an account's server (`waits.v1`). Failures are [run.ostrom.app.domain.models.AppException]s. */
interface WaitRepository {
    /**
     * The member's waits in [status] that a person answers, newest first, from the page after [before].
     */
    suspend fun list(accountId: String, status: WaitStatus, before: String? = null): WaitPage
    /** `409 wait_closed` ([run.ostrom.app.domain.models.AppException.Server]) when someone answered or cancelled it first. */
    suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait

    /**
     * The open waits that resolve by themselves (a time, a webhook), soonest first: at most one page of each kind, since they wait
     * for nobody and a session holds few. Not part of [list], which is what waits for the member. A repository without them lists none.
     */
    suspend fun inProgress(accountId: String): List<Wait> = emptyList()
}
