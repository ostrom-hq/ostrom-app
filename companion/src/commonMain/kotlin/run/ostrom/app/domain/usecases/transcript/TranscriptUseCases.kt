// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/transcript/TranscriptUseCases.kt; see NOTICE.
package run.ostrom.app.domain.usecases.transcript

import kotlinx.coroutines.flow.Flow
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.KnownAgent
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TranscriptPage
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.announced
import run.ostrom.app.domain.repositories.TranscriptRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard

class LoadTranscriptUseCase(private val transcripts: TranscriptRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    /**
     * One page of history. [feed] (`main`, or a subagent's id) asks for one agent's events alone; a server that does not
     * announce `runners.cloud.transcript.feeds.v1` would ignore it, so it is not sent there and every event comes, as before.
     */
    suspend operator fun invoke(accountId: String, sessionId: String, before: Long? = null, feed: String? = null): TranscriptPage = guard(accountId) {
        val announced = features.announced(accountId)
        if (Features.RUNNERS_CLOUD_CONVERSATION !in announced) throw AppException.Unsupported(Features.RUNNERS_CLOUD_CONVERSATION)
        if (feed != null && Features.RUNNERS_CLOUD_TRANSCRIPT_FEEDS in announced) transcripts.feedPage(accountId, sessionId, before, feed) else transcripts.page(accountId, sessionId, before)
    }

    /** The server pages one agent's events alone and lists subagents (`runners.cloud.transcript.feeds.v1`). */
    suspend fun feeds(accountId: String): Boolean = guard(accountId) { Features.RUNNERS_CLOUD_TRANSCRIPT_FEEDS in features.announced(accountId) }
}

/** The session's subagents as the server tracks them; empty on a server without `runners.cloud.transcript.feeds.v1`. */
class ListAgentsUseCase(private val transcripts: TranscriptRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, sessionId: String): List<KnownAgent> = guard(accountId) {
        if (Features.RUNNERS_CLOUD_TRANSCRIPT_FEEDS !in features.announced(accountId)) emptyList() else transcripts.agents(accountId, sessionId)
    }
}

class FollowTranscriptUseCase(private val transcripts: TranscriptRepository, private val guard: AccountGuard) {
    operator fun invoke(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> =
        guard.flow(accountId, transcripts.follow(accountId, sessionId, after))
}

/**
 * The events a session screen shows: history pages and live events merged by sequence, each once, in order.
 * Pure: every merge returns a new transcript.
 */
data class Transcript(
    val events: List<TranscriptEvent> = emptyList(),
    /** The cursor of the next older page; null when everything kept was loaded (or nothing yet). */
    val nextBefore: Long? = null,
    /** The newest sequence known, from events or from a page's `lastSeq`: following resumes after it. */
    val newestSeq: Long = 0,
    /**
     * The subagent feeds read (`runners.cloud.transcript.feeds.v1`), each with the cursor of its next older page; null once all of
     * it was loaded. A feed that is not here was never read. The main agent's cursor is [nextBefore].
     */
    val feeds: Map<String, Long?> = emptyMap(),
) {
    /** A page of one subagent's feed, the newest or an older one: its own cursor moves, the main agent's and [newestSeq] do not. */
    fun withFeed(feed: String, page: TranscriptPage): Transcript = merged(page.events).let { it.copy(feeds = it.feeds + (feed to page.nextBefore)) }


    /** The first page: the newest events. */
    fun withLatest(page: TranscriptPage): Transcript = merged(page.events).copy(nextBefore = page.nextBefore).let { it.copy(newestSeq = maxOf(it.newestSeq, page.lastSeq)) }

    fun withOlder(page: TranscriptPage): Transcript = merged(page.events).copy(nextBefore = page.nextBefore)

    fun withLive(event: TranscriptEvent): Transcript = merged(listOf(event))

    private fun merged(incoming: List<TranscriptEvent>): Transcript {
        if (incoming.isEmpty()) return this
        val last = events.lastOrNull()?.seq ?: 0
        val sorted = incoming.sortedBy { it.seq }
        val combined = if (sorted.first().seq > last) {
            events + sorted.distinctBy { it.seq }
        } else {
            // Known sequences keep the first copy: an event never changes once written.
            (events + sorted).distinctBy { it.seq }.sortedBy { it.seq }
        }
        return copy(events = combined, newestSeq = maxOf(newestSeq, combined.last().seq))
    }
}
