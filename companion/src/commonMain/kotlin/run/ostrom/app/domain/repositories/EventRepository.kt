// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's SessionRepository.sessionChanges (Apache-2.0), companion/.../domain/repositories/SessionRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import kotlinx.coroutines.flow.Flow
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.OrgChange

/** The organization's event feed of an account's server (`events.v1`). */
interface EventRepository {
    /**
     * The events of [types] the member may see, from now on: [LiveUpdate.Opened] once the server accepted the stream,
     * then each event; it reconnects by itself after a drop, resuming after the last event, and ends only with an
     * [run.ostrom.app.domain.models.AppException] (a refusal, a credential the server stopped accepting).
     */
    fun changes(accountId: String, types: Set<String>): Flow<LiveUpdate<OrgChange>>
}
