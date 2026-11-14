// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/sessions/SessionUseCases.kt; see NOTICE.
package run.ostrom.app.domain.usecases.sessions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import run.ostrom.app.domain.models.AgentSession
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.SessionFilter
import run.ostrom.app.domain.models.SessionPage
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.SessionRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.events.FeedSignal
import run.ostrom.app.domain.usecases.events.OrgFeed
import run.ostrom.app.domain.usecases.events.signals

/** One page of the organization's sessions in [filter], newest first; [AppException.Unsupported] from a server without them. */
class LoadSessionsUseCase(private val sessions: SessionRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, filter: SessionFilter, before: String? = null): SessionPage = guard(accountId) {
        val announced = features.capabilities(accountId).features
        if (Features.SESSIONS !in announced) throw AppException.Unsupported(Features.SESSIONS)
        sessions.sessions(accountId, filter.open, before)
    }
}

/**
 * A session's record and what the app can open from it: its receipt in the History tab when the server has receipts
 * ([historyServed]), its workflow run when the server has workflows ([runsServed]), its todo when it has todos
 * ([todosServed]).
 */
data class SessionDetails(val session: AgentSession, val historyServed: Boolean, val runsServed: Boolean, val todosServed: Boolean)

class GetSessionUseCase(private val sessions: SessionRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, sessionId: String): SessionDetails = guard(accountId) {
        if (sessionId.isBlank()) throw AppException.NotFound()
        val announced = features.capabilities(accountId).features
        if (Features.SESSIONS !in announced) throw AppException.Unsupported(Features.SESSIONS)
        SessionDetails(
            sessions.session(accountId, sessionId),
            historyServed = Features.RECEIPTS in announced,
            runsServed = Features.WORKFLOWS in announced,
            todosServed = Features.TODOS in announced,
        )
    }
}

/**
 * Keeps the sessions list current. Ostrom publishes no session event: the list follows the app's one feed ([OrgFeed])
 * for its state, loads when it opens and after a gap, and loads again on return and on refresh; from a server without
 * the feed, once. A server without `sessions.v1` yields nothing.
 */
class FollowSessionsUseCase(private val feed: OrgFeed, private val features: FeaturesRepository, private val guard: AccountGuard) {
    operator fun invoke(accountId: String): Flow<FeedSignal> = guard.flow(accountId, flow {
        if (Features.SESSIONS !in features.capabilities(accountId).features) return@flow
        emitAll(feed.signals(accountId, features, types = emptySet()))
    })
}
