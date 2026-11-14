// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../domain/SessionFeedTest.kt; see NOTICE.
package run.ostrom.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeEventRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.usecases.events.OrgFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The app's one connection to the organization's feed, shared by every screen that follows it. */
@OptIn(ExperimentalCoroutinesApi::class)
class OrgFeedTest {
    private val events = FakeEventRepository()
    private val wire = Channel<LiveUpdate<OrgChange>>(Channel.UNLIMITED)
    private val run = OrgChange(OrgChange.RUN_UPDATED, "r1", "m1", "wf1")

    init { events.changes = flow { for (update in wire) emit(update) } }

    private fun TestScope.feed(features: FakeFeatures = FakeFeatures(setOf(Features.EVENTS, Features.WAITS))) = OrgFeed(events, features, backgroundScope)

    @Test fun twoScreensShareOneConnectionAndTheLaterOneLoadsAtOnce() = runTest {
        val feed = feed()
        val first = mutableListOf<LiveUpdate<OrgChange>>()
        val second = mutableListOf<LiveUpdate<OrgChange>>()
        val a = launch { feed.follow("a1").collect { first += it } }
        runCurrent()
        wire.send(LiveUpdate.Opened(false)); runCurrent()
        // Joins a feed already open: it is told to load now, as the first one was when the feed opened.
        val b = launch { feed.follow("a1").collect { second += it } }
        runCurrent()
        wire.send(LiveUpdate.Item(run)); runCurrent()
        assertEquals(1, events.asked.size)
        assertEquals(1, feed.openConnections())
        assertEquals(listOf(LiveUpdate.Opened(false), LiveUpdate.Item(run)), first)
        assertEquals(listOf(LiveUpdate.Opened(false), LiveUpdate.Item(run)), second)
        a.cancel(); b.cancel()
    }

    @Test fun theConnectionClosesAfterTheLastScreenLeftUnlessOneComesBackInTime() = runTest {
        val feed = feed()
        var connection = launch { feed.follow("a1").collect { } }
        runCurrent()
        connection.cancel(); runCurrent()
        // Back within the delay (the list replaced by a record, then back): the same connection.
        advanceTimeBy(4_000); runCurrent()
        connection = launch { feed.follow("a1").collect { } }
        runCurrent()
        assertEquals(1, events.asked.size)
        connection.cancel(); runCurrent()
        advanceTimeBy(5_001); runCurrent()
        // Nobody follows any more (the app in the background, the account signed out): closed.
        assertEquals(0, feed.openConnections())
        connection = launch { feed.follow("a1").collect { } }
        runCurrent()
        assertEquals(2, events.asked.size)
        connection.cancel()
    }

    @Test fun eachAccountHasItsOwnConnection() = runTest {
        val feed = feed()
        val a = launch { feed.follow("a1").collect { } }
        val b = launch { feed.follow("a2").collect { } }
        runCurrent()
        assertEquals(2, feed.openConnections())
        a.cancel(); b.cancel()
    }

    @Test fun aScreenJoiningDuringAReconnectionLoadsWhenTheFeedIsBack() = runTest {
        val feed = feed()
        val first = launch { feed.follow("a1").collect { } }
        runCurrent()
        wire.send(LiveUpdate.Opened(false)); wire.send(LiveUpdate.Reconnecting(2_000)); runCurrent()
        val seen = mutableListOf<LiveUpdate<OrgChange>>()
        val late = launch { feed.follow("a1").collect { seen += it } }
        runCurrent()
        wire.send(LiveUpdate.Opened(true)); runCurrent()
        assertEquals(listOf<LiveUpdate<OrgChange>>(LiveUpdate.Opened(false)), seen)
        first.cancel(); late.cancel()
    }

    @Test fun aFailureEndsEveryScreenAndTheNextOneConnectsAgain() = runTest {
        val feed = feed()
        events.changes = flow { throw AppException.Unauthorized() }
        val failures = List(2) {
            async {
                try {
                    feed.follow("a1").collect { }
                    null
                } catch (error: AppException) {
                    error
                }
            }
        }
        runCurrent()
        failures.forEach { assertIs<AppException.Unauthorized>(it.await()) }
        assertEquals(0, feed.openConnections())
        events.changes = flow { emit(LiveUpdate.Opened(false)) }
        assertEquals(listOf<LiveUpdate<OrgChange>>(LiveUpdate.Opened(false)), feed.follow("a1").toList())
    }

    @Test fun aStreamCancelledFromElsewhereEndsAsUnreachableInsteadOfLeavingTheScreensWaiting() = runTest {
        val feed = feed()
        // An HTTP client closed under the stream throws a cancellation the connection did not ask for.
        events.changes = flow { emit(LiveUpdate.Opened(false)); throw CancellationException("client closed") }
        val failure = try {
            feed.follow("a1").collect { }
            null
        } catch (error: AppException) {
            error
        }
        assertIs<AppException.Unreachable>(failure)
    }

    @Test fun theConnectionAsksForTheUnionOfTheTypesTheServerHas() = runTest {
        val feed = feed(FakeFeatures(setOf(Features.EVENTS, Features.WAITS, Features.WORKFLOWS, Features.TODOS, Features.SESSIONS)))
        val connection = launch { feed.follow("a1").collect { } }
        runCurrent()
        assertEquals(listOf(OrgChange.WAIT_TYPES + OrgChange.RUN_UPDATED + OrgChange.TODO_UPDATED), events.asked)
        connection.cancel()
        // A server without workflows or todos is never asked for their types, which it may not know.
        assertEquals(OrgChange.WAIT_TYPES, OrgChange.typesFor(setOf(Features.EVENTS, Features.WAITS, Features.SESSIONS)))
        assertEquals(emptySet(), OrgChange.typesFor(setOf(Features.EVENTS)))
    }

    @Test fun aSlowScreenSlowsTheReadingInsteadOfDroppingUpdates() = runTest {
        val feed = feed()
        val gate = Channel<Unit>()
        val seen = mutableListOf<LiveUpdate<OrgChange>>()
        val slow = launch { feed.follow("a1").collect { seen += it; gate.receive() } }
        runCurrent()
        repeat(200) { wire.send(LiveUpdate.Item(run.copy(subjectId = "r${it + 1}"))) }
        runCurrent()
        repeat(200) { gate.send(Unit); runCurrent() }
        assertEquals((1..200).map { "r$it" }, seen.map { (it as LiveUpdate.Item).value.subjectId })
        slow.cancel()
        gate.close()
        wire.close()
    }
}
