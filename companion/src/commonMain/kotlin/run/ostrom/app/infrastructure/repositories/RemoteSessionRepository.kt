// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's RemoteSessionRepository (Apache-2.0), companion/.../infrastructure/repositories/RemoteSessionRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.AgentSession
import run.ostrom.app.domain.models.SessionGateAnswer
import run.ostrom.app.domain.models.SessionPage
import run.ostrom.app.domain.repositories.SessionRepository
import run.ostrom.core.api.models.Session as ApiSession

/** The sessions over the typed client (`/api/v1/sessions`). */
class RemoteSessionRepository(private val calls: AccountCalls) : SessionRepository {
    override suspend fun sessions(accountId: String, open: Boolean?, before: String?): SessionPage = calls.withAccount(accountId) { client ->
        val page = client.sessions.list(open = open, before = before, limit = PAGE)
        SessionPage(page.items.map { it.domain() }, page.next)
    }

    override suspend fun session(accountId: String, sessionId: String): AgentSession =
        calls.withAccount(accountId, resource = true) { it.sessions.get(sessionId).session.domain() }

    private companion object { const val PAGE = 50 }
}

internal fun ApiSession.domain() = AgentSession(
    id = id,
    repo = realRepo ?: repo,
    agent = agent,
    model = model,
    dev = dev,
    startedAt = millisOf(startedAt),
    endedAt = millisOf(endedAt),
    endedBy = endedBy,
    lastSeenAt = millisOf(lastSeenAt),
    tools = tools,
    costUsd = costUsd,
    exitCode = exitCode,
    segment = segment,
    receiptId = receiptId,
    runId = runId,
    // A session the server marks ended without an end time (a launcher declared lost) is not shown as open.
    lost = lost == true || (ended == true && endedAt == null),
    activity = activity,
    gateAnswers = gateAnswers.map { SessionGateAnswer(it.gateId, it.actionClass, it.outcome, it.by, millisOf(it.at), it.receiptId) },
)
