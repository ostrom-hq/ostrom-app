// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/TranscriptRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import kotlinx.coroutines.flow.Flow
import run.ostrom.app.domain.models.KnownAgent
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TranscriptPage

interface TranscriptRepository {
    /** The newest events before [before] (the newest of all when null), oldest first. */
    suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage

    /**
     * Like [page], for one agent's events: [feed] is `main` (the main agent's own) or a subagent's id; null for every
     * event. Only for a server that announces `cloud.transcript.feeds.v1`, which ignores a `feed` it does not know.
     */
    suspend fun feedPage(accountId: String, sessionId: String, before: Long?, feed: String?): TranscriptPage = page(accountId, sessionId, before)

    /** The session's subagents with their real state (`cloud.transcript.feeds.v1`), oldest first. */
    suspend fun agents(accountId: String, sessionId: String): List<KnownAgent> = emptyList()

    /** Events after [after], live, reconnecting by itself from the last one delivered; completes after `Ended`. */
    fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>>
}
