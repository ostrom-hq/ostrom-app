// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's RemoteSessionRepository.sessionChanges (Apache-2.0),
// companion/.../infrastructure/repositories/RemoteSessionRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.repositories.EventRepository
import run.ostrom.app.infrastructure.stream.ReconnectPolicy
import run.ostrom.app.infrastructure.stream.reconnecting
import run.ostrom.core.client.OstromClient
import run.ostrom.core.client.OstromException
import run.ostrom.core.client.StreamEvent

/** The organization's feed over the typed client (`GET /api/v1/events`), reconnecting by itself. */
class RemoteEventRepository(private val calls: AccountCalls, private val policy: ReconnectPolicy = ReconnectPolicy()) : EventRepository {
    override fun changes(accountId: String, types: Set<String>): Flow<LiveUpdate<OrgChange>> = flow {
        // One cursor per collection, shared by its attempts: a reconnection resumes after the last event seen.
        var cursor: Long? = null
        emitAll(reconnecting(policy) { send, progress ->
            val client = calls.client(accountId)
            val resumed = cursor != null
            client.events.follow(
                after = cursor,
                types = types,
                // The stream goes on at the oldest event still held: a reconnection before it resumes there, not
                // from the purged cursor again (another gap, another load).
                onGap = { oldest -> send(LiveUpdate.Gap); cursor = maxOf(cursor ?: 0, oldest - 1) },
                onOpen = { progress.opened(); send(LiveUpdate.Opened(resumed)) },
            ) { event ->
                val read = event.read()
                send(LiveUpdate.Item(OrgChange(event.type, read.subjectId, read.memberId, read.workflowId())))
                cursor = read.seq
                progress.itemSent()
            }
        })
    }
}

/** The fields of an `OrgEvent` the app reads; the others (a newer server's included) are ignored. */
@Serializable
private class FeedEvent(val seq: Long, val subjectId: String, val memberId: String? = null, val data: Map<String, JsonElement> = emptyMap()) {
    /** A run event's workflow; null for the other types, or a value that is not a string. */
    fun workflowId(): String? = (data["workflowId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

private val FEED_JSON = Json { ignoreUnknownKeys = true }

private fun StreamEvent.read(): FeedEvent = try {
    FEED_JSON.decodeFromJsonElement(FeedEvent.serializer(), data)
} catch (_: IllegalArgumentException) {
    // Never the payload in the message: it is the member's data.
    throw OstromException(0, OstromClient.MALFORMED_EVENT, "The event stream sent a malformed event.")
}
