// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/RemoteTranscriptRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import run.ostrom.app.domain.models.KnownAgent
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TranscriptPage
import run.ostrom.app.domain.repositories.TranscriptRepository
import run.ostrom.app.infrastructure.stream.ReconnectPolicy
import run.ostrom.app.infrastructure.stream.reconnecting

/** A conversation session's transcript over the typed client (`client.cloudTranscripts`), followed live and reconnecting by itself. */
class RemoteTranscriptRepository(private val calls: AccountCalls, private val policy: ReconnectPolicy = ReconnectPolicy()) : TranscriptRepository {
    override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage = feedPage(accountId, sessionId, before, null)

    override suspend fun feedPage(accountId: String, sessionId: String, before: Long?, feed: String?): TranscriptPage =
        // Without a cursor the server pages from the oldest event: the newest page is the one before any sequence.
        calls.withAccount(accountId, resource = true) { it.cloudTranscripts.events(sessionId, before = before ?: NEWEST, limit = PAGE, feed = feed).domain() }

    override suspend fun agents(accountId: String, sessionId: String): List<KnownAgent> =
        calls.withAccount(accountId, resource = true) { it.cloudTranscripts.agents(sessionId).agents.map { agent -> agent.domain() } }

    override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> = flow {
        // One cursor per collection: each reconnection resumes after the last event delivered.
        var cursor = after
        emitAll(reconnecting(policy, resource = true) { send, progress ->
            val client = calls.client(accountId)
            client.cloudTranscripts.followEvents(sessionId, cursor, onOpen = { progress.opened(); send(LiveUpdate.Opened(resumed = true)) }) { event ->
                send(LiveUpdate.Item(event.domain()))
                cursor = event.seq
                progress.itemSent()
            }
        })
    }

    private companion object {
        const val PAGE = 100
        const val NEWEST = Long.MAX_VALUE
    }
}
