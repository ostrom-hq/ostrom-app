// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/stream/Reconnection.kt; see NOTICE.
package run.ostrom.app.infrastructure.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.transform
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.infrastructure.repositories.translated
import run.ostrom.core.client.OstromClient
import run.ostrom.core.client.OstromException
import run.ostrom.core.client.StreamEnd
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * When to reconnect a stream: [firstDelayMillis] after the first failure, then doubling up to [maxDelayMillis], each
 * delay shortened by up to a fifth at random so clients cut together do not return together. The count restarts when
 * an item arrives, or when an accepted connection stayed open at least [stableMillis] (a quiet stream cut by a proxy
 * now and then); a connection accepted then dropped at once keeps backing off. A `429` waits at least its
 * `Retry-After`, bounded by [maxRetryAfterMillis].
 */
class ReconnectPolicy(
    private val firstDelayMillis: Long = 2_000,
    private val maxDelayMillis: Long = 30_000,
    private val maxRetryAfterMillis: Long = 300_000,
    private val random: Random = Random.Default,
    val stableMillis: Long = 30_000,
    /** Monotonic milliseconds. */
    val now: () -> Long = monotonicMillis(),
) {
    fun delayMillis(failures: Int, retryAfterSeconds: Long? = null): Long {
        val base = (firstDelayMillis shl (failures - 1).coerceIn(0, 16)).coerceAtMost(maxDelayMillis)
        val jittered = base - (base * random.nextDouble(0.0, 0.2)).toLong()
        val floor = retryAfterSeconds?.let { (it * 1_000).coerceAtMost(maxRetryAfterMillis) } ?: 0
        return maxOf(jittered, floor)
    }
}

/** What [reconnecting] needs to hear from a connection: accepted, and each item sent. */
internal interface StreamProgress {
    fun opened()
    fun itemSent()
}

private fun monotonicMillis(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

/**
 * Runs [connect] again and again: a network failure, a closed stream, a `408`, `429` or `5xx` reconnect after the
 * policy's delay ([LiveUpdate.Reconnecting] says when); `DENIED` and every other refusal end the flow with an
 * [AppException] (a `401` or `DENIED` is [AppException.Unauthorized]); an unreadable event ends it too, since a
 * reconnection would replay it. [connect] keeps its own cursor so that each attempt resumes after the last item it
 * sent, and calls [StreamProgress.opened] once accepted and [StreamProgress.itemSent] after each item.
 */
internal fun <T> reconnecting(
    policy: ReconnectPolicy,
    connect: suspend (send: suspend (LiveUpdate<T>) -> Unit, progress: StreamProgress) -> StreamEnd,
): Flow<LiveUpdate<T>> = channelFlow<Any> {
    var failures = 0
    var openedAt: Long? = null
    val progress = object : StreamProgress {
        override fun opened() { openedAt = policy.now() }
        override fun itemSent() { failures = 0 }
    }
    while (true) {
        openedAt = null
        val end = try {
            connect({ send(it) }, progress)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!error.isTransient()) { send(Stop(error.translated())); return@channelFlow }
            if (openedAt?.let { policy.now() - it >= policy.stableMillis } == true) failures = 0
            failures++
            val wait = policy.delayMillis(failures, (error as? OstromException)?.retryAfterSeconds)
            send(LiveUpdate.Reconnecting(wait))
            delay(wait)
            continue
        }
        when (end) {
            // The organization's feed never ends by itself; were it to, the next attempt resumes it.
            StreamEnd.ENDED -> continue
            StreamEnd.DENIED -> send(Stop(AppException.Unauthorized()))
        }
        return@channelFlow
    }
    // The producer always completes normally: a failing channel flow is cancelled, and cancellation could discard an
    // update already read. The failure travels as the last element instead and is thrown on the collector's side.
    // Rendezvous: each update is handed over before the next is read, so a slow collector slows the reading, not memory.
}.buffer(Channel.RENDEZVOUS).transform { element ->
    if (element is Stop) throw element.error
    @Suppress("UNCHECKED_CAST")
    emit(element as LiveUpdate<T>)
}

private class Stop(val error: Exception)

private fun Exception.isTransient(): Boolean = when (this) {
    is AppException -> false
    is OstromException -> when {
        type == OstromClient.MALFORMED_EVENT -> false
        else -> status == 0 || status == 408 || status == 429 || status >= 500
    }
    // A bug or the typed client's own refusal of an argument: retrying cannot help. Engines throw subclasses for
    // network failures, so only the exact type.
    else -> this::class != IllegalArgumentException::class && translated() is AppException.Unreachable
}
