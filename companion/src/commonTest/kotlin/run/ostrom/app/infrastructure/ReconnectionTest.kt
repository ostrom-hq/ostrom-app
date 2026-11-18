// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.infrastructure.stream.ReconnectPolicy
import run.ostrom.app.infrastructure.stream.reconnecting
import run.ostrom.core.client.OstromClient
import run.ostrom.core.client.OstromException
import run.ostrom.core.client.StreamEnd
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** When a stream reconnects, how long it waits, and which failures end it instead. */
class ReconnectionTest {
    /** A random source that never shortens a delay, so the delays are exactly the policy's. */
    private object NoJitter : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    private fun policy(now: () -> Long = { 0L }) =
        ReconnectPolicy(firstDelayMillis = 100, maxDelayMillis = 1_000, maxRetryAfterMillis = 5_000, random = NoJitter, stableMillis = 500, now = now)

    private suspend fun <T> Flow<LiveUpdate<T>>.drain(): Pair<List<LiveUpdate<T>>, Exception?> {
        val seen = mutableListOf<LiveUpdate<T>>()
        val failure = try {
            collect { seen += it }
            null
        } catch (error: Exception) {
            error
        }
        return seen to failure
    }

    @Test fun theDelayDoublesFromTheFirstOneUpToTheMaximum() {
        val policy = policy()
        assertEquals(listOf(100L, 200L, 400L, 800L, 1_000L, 1_000L), (1..6).map { policy.delayMillis(it) })
        assertEquals(100L, policy.delayMillis(0), "no failure yet waits the first delay")
    }

    @Test fun theJitterShortensAtMostAFifthOfTheDelay() {
        val policy = ReconnectPolicy(firstDelayMillis = 1_000, maxDelayMillis = 1_000, random = Random(7))
        val delays = (1..50).map { policy.delayMillis(1) }
        assertTrue(delays.all { it in 800L..1_000L }, delays.toString())
        assertTrue(delays.distinct().size > 1, "the delays are spread, not all the same")
    }

    @Test fun aRetryAfterRaisesTheDelayButIsBoundedByTheMaximumRetryAfter() {
        val policy = policy()
        assertEquals(3_000L, policy.delayMillis(1, retryAfterSeconds = 3))
        assertEquals(5_000L, policy.delayMillis(1, retryAfterSeconds = 3_600), "bounded by maxRetryAfterMillis")
        assertEquals(1_000L, policy.delayMillis(10, retryAfterSeconds = 0), "a retry-after below the delay does not shorten it")
    }

    @Test fun theDefaultPolicyReadsAMonotonicClock() {
        val policy = ReconnectPolicy()
        val first = policy.now()
        val second = policy.now()
        assertTrue(first >= 0 && second >= first, "$first then $second")
        assertEquals(30_000L, policy.stableMillis)
    }

    @Test fun aDeniedStreamEndsAsUnauthorizedWithoutReconnecting() = runTest {
        var connections = 0
        val (seen, failure) = reconnecting<String>(policy()) { _, _ -> connections++; StreamEnd.DENIED }.drain()
        assertTrue(seen.isEmpty())
        assertIs<AppException.Unauthorized>(failure)
        assertEquals(1, connections)
    }

    @Test fun aResourceStreamThatEndsReportsItAndCompletes() = runTest {
        var connections = 0
        val (seen, failure) = reconnecting<String>(policy(), resource = true) { send, _ ->
            connections++
            send(LiveUpdate.Item("last"))
            StreamEnd.ENDED
        }.drain()
        assertNull(failure)
        assertEquals(listOf(LiveUpdate.Item("last"), LiveUpdate.Ended), seen)
        assertEquals(1, connections)
    }

    @Test fun aFeedThatEndsIsResumedAfterTheSameDelayLikeADroppedConnection() = runTest {
        var connections = 0
        val (seen, failure) = reconnecting<String>(policy()) { _, _ ->
            if (++connections < 3) StreamEnd.ENDED else StreamEnd.DENIED
        }.drain()
        assertIs<AppException.Unauthorized>(failure)
        assertEquals(listOf<LiveUpdate<String>>(LiveUpdate.Reconnecting(100), LiveUpdate.Reconnecting(200)), seen)
        assertEquals(3, connections)
    }

    @Test fun transientFailuresReconnectWithGrowingDelaysAndTheServersRetryAfter() = runTest {
        val failures = listOf<Exception>(
            OstromException(503, null, "down"),
            OstromException(429, null, "slow down", retryAfterSeconds = 2),
            OstromException(408, null, "timeout"),
            OstromException(0, null, "closed"),
            Exception("connection reset"),
        )
        var connections = 0
        val (seen, failure) = reconnecting<String>(policy()) { _, _ ->
            failures.getOrNull(connections++)?.let { throw it }
            StreamEnd.DENIED
        }.drain()
        assertIs<AppException.Unauthorized>(failure)
        assertEquals(
            listOf<LiveUpdate<String>>(
                LiveUpdate.Reconnecting(100), LiveUpdate.Reconnecting(2_000), LiveUpdate.Reconnecting(400),
                LiveUpdate.Reconnecting(800), LiveUpdate.Reconnecting(1_000),
            ),
            seen,
        )
    }

    @Test fun aSubclassOfIllegalArgumentExceptionIsANetworkFailureAndReconnects() = runTest {
        // Engines throw subclasses of IllegalArgumentException (an unresolved address) for network failures.
        var connections = 0
        val (seen, failure) = reconnecting<String>(policy()) { _, _ ->
            if (connections++ == 0) throw NumberFormatException("host") else StreamEnd.DENIED
        }.drain()
        assertIs<AppException.Unauthorized>(failure)
        assertEquals(listOf<LiveUpdate<String>>(LiveUpdate.Reconnecting(100)), seen)
    }

    @Test fun anItemOrAStableConnectionRestartsTheBackoff() = runTest {
        var clock = 0L
        var connections = 0
        val (seen, _) = reconnecting<String>(policy { clock }) { _, progress ->
            when (connections++) {
                0, 1 -> throw OstromException(503, null, "down")
                2 -> { progress.opened(); progress.itemSent(); throw OstromException(503, null, "down") }
                3 -> { progress.opened(); clock += 500; throw OstromException(503, null, "down") }
                4 -> { progress.opened(); clock += 499; throw OstromException(503, null, "down") }
                else -> StreamEnd.DENIED
            }
        }.drain()
        // 100, 200 back off; an item restarts at 100; a connection open for the stable time restarts at 100; one cut just
        // before keeps backing off.
        assertEquals(listOf<LiveUpdate<String>>(LiveUpdate.Reconnecting(100), LiveUpdate.Reconnecting(200), LiveUpdate.Reconnecting(100), LiveUpdate.Reconnecting(100), LiveUpdate.Reconnecting(200)), seen)
    }

    @Test fun aRefusalEndsTheFlowWithTheTranslatedFailureAfterTheItemsAlreadySent() = runTest {
        val (seen, failure) = reconnecting<String>(policy()) { send, _ ->
            send(LiveUpdate.Item("kept"))
            throw OstromException(403, "forbidden", "no")
        }.drain()
        assertEquals(listOf<LiveUpdate<String>>(LiveUpdate.Item("kept")), seen)
        val server = assertIs<AppException.Server>(failure)
        assertEquals(403, server.statusCode)
    }

    @Test fun aResourceStreamsNotFoundIsAGoneResourceAndAFeedsIsARefusal() = runTest {
        val (_, resource) = reconnecting<String>(policy(), resource = true) { _, _ -> throw OstromException(404, null, "gone") }.drain()
        assertIs<AppException.NotFound>(resource)
        val (_, feed) = reconnecting<String>(policy()) { _, _ -> throw OstromException(404, null, "gone") }.drain()
        assertIs<AppException.Server>(feed)
    }

    @Test fun anUnreadableEventEndsTheFlowBecauseAReconnectionWouldReplayIt() = runTest {
        var connections = 0
        val (_, failure) = reconnecting<String>(policy()) { _, _ ->
            connections++
            throw OstromException(0, OstromClient.MALFORMED_EVENT, "unreadable")
        }.drain()
        assertIs<AppException.Server>(failure)
        assertEquals(1, connections)
    }

    @Test fun anUnauthorizedAnswerEndsTheFlowAsUnauthorized() = runTest {
        val (_, failure) = reconnecting<String>(policy()) { _, _ -> throw OstromException(401, null, "no") }.drain()
        assertIs<AppException.Unauthorized>(failure)
    }

    @Test fun aDomainFailureAnArgumentErrorAndAnUnreadableBodyAreNotRetried() = runTest {
        for (error in listOf<Exception>(AppException.NotOstrom(), IllegalArgumentException("bad argument"), SerializationException("not ours"))) {
            var connections = 0
            val (seen, failure) = reconnecting<String>(policy()) { _, _ -> connections++; throw error }.drain()
            assertTrue(seen.isEmpty(), "$error")
            assertEquals(1, connections, "$error")
            assertTrue(failure is AppException.NotOstrom || failure is IllegalArgumentException, "$error became $failure")
        }
    }

    @Test fun aCancellationIsNeverSwallowedNorRetried() = runTest {
        var connections = 0
        val (_, failure) = reconnecting<String>(policy()) { _, _ -> connections++; throw CancellationException("stop") }.drain()
        assertIs<CancellationException>(failure)
        assertEquals(1, connections)
    }
}
