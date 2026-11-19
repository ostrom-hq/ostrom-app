// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeEventRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.usecases.events.FeedSignal
import run.ostrom.app.domain.usecases.events.OrgFeed
import run.ostrom.app.domain.usecases.events.signals
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OrgFeedEdgeCasesTest {
    private val events = FakeEventRepository()
    private val features = FakeFeatures(setOf(Features.EVENTS, Features.TODOS))

    @Test
    fun aFollowerThatJoinsAConnectionAsItFailsIsToldTheFailure() = runTest {
        // The connection runs at once (unconfined) and fails before the follower is subscribed: the follower reads the
        // end the connection kept, instead of waiting for an update that never comes.
        events.changes = flow { throw AppException.Unreachable() }
        val feed = OrgFeed(events, features, CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))
        assertFailsWith<AppException.Unreachable> { feed.follow("a1").toList() }
        assertEquals(0, feed.openConnections())
    }

    @Test
    fun aConnectionThatEndsAfterItWasClosedLeavesTheNextOneAlone() = runTest {
        // A stream that finishes quietly when cancelled: its end must not remove a connection that is not its own.
        events.changes = flow {
            emit(LiveUpdate.Opened(resumed = false))
            try { awaitCancellation() } catch (_: CancellationException) { }
        }
        val feed = OrgFeed(events, features, backgroundScope)
        val follower = launch { feed.follow("a1").collect { } }
        runCurrent()
        follower.cancel(); runCurrent()
        advanceTimeBy(5_001); runCurrent()
        assertEquals(0, feed.openConnections())

        val next = launch { feed.follow("a1").collect { } }
        runCurrent()
        assertEquals(1, feed.openConnections())
        assertEquals(2, events.asked.size)
        next.cancel()
    }

    @Test
    fun aScreensSignalsFollowTheConnectionAndIgnoreAnEnd() = runTest {
        val change = OrgChange(OrgChange.TODO_UPDATED, "s1", "m1")
        events.changes = flowOf(
            LiveUpdate.Opened(resumed = true),
            LiveUpdate.Item(change),
            LiveUpdate.Item(OrgChange(OrgChange.WAIT_CREATED, "w1", "m1")),
            LiveUpdate.Reconnecting(1_000),
            LiveUpdate.Gap,
            LiveUpdate.Ended,
        )
        val signals = OrgFeed(events, features, backgroundScope).signals("a1", features, setOf(OrgChange.TODO_UPDATED)).toList()
        assertEquals(
            listOf(FeedSignal.Connection(reconnecting = false), FeedSignal.Reload, FeedSignal.Changed(change), FeedSignal.Connection(reconnecting = true), FeedSignal.Reload),
            signals,
        )
    }

    @Test
    fun aServerWithTheFeedButNoTypeTheAppReadsLoadsOnce() = runTest {
        val noTypes = FakeFeatures(setOf(Features.EVENTS))
        assertEquals(listOf<FeedSignal>(FeedSignal.Reload), OrgFeed(events, noTypes, backgroundScope).signals("a1", noTypes, emptySet()).toList())
        assertEquals(0, events.asked.size)
    }

    @Test
    fun aClosingDueAfterItsConnectionEndedLeavesTheNextConnectionOpen() = runTest {
        // The first stream ends by itself while its connection lingers; a follower then opens a second connection, which
        // the first one's closing, when it falls due, must leave alone.
        val ending = CompletableDeferred<Unit>()
        var calls = 0
        events.changes = flow {
            calls++
            emit(LiveUpdate.Opened(resumed = false))
            if (calls == 1) ending.await() else awaitCancellation()
        }
        val feed = OrgFeed(events, features, backgroundScope)
        val first = launch { feed.follow("a1").collect { } }
        runCurrent()
        first.cancel(); runCurrent()
        advanceTimeBy(1_000)
        ending.complete(Unit); runCurrent()
        assertEquals(0, feed.openConnections())

        val second = launch { feed.follow("a1").collect { } }
        runCurrent()
        advanceTimeBy(4_500); runCurrent()
        assertEquals(1, feed.openConnections())
        assertTrue(second.isActive)
        assertEquals(2, calls)
        second.cancel()
    }
}
