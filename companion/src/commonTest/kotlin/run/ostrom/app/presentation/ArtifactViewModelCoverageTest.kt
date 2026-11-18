// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Artifact
import run.ostrom.app.domain.models.ArtifactPage
import run.ostrom.app.domain.models.ArtifactTicket
import run.ostrom.app.domain.models.ArtifactVersion
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.repositories.ArtifactRepository
import run.ostrom.app.domain.usecases.artifacts.GetArtifactUseCase
import run.ostrom.app.domain.usecases.artifacts.OpenArtifactUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.presentation.navigation.ArtifactRoute
import run.ostrom.app.presentation.viewmodels.ArtifactViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactViewModelCoverageTest {
    private val dispatcher = StandardTestDispatcher()
    private val id = "0b5c7c1e-9f0a-4d2b-8e5f-3a1c2d4e6f80"
    private val repo = Repo()

    private inner class Repo : ArtifactRepository {
        val calls = mutableListOf<String>()
        var latest = 2
        var versionList: List<Int> = listOf(2, 1)
        var artifactGate: CompletableDeferred<Unit>? = null
        var artifactError: Exception? = null
        var tickets: suspend (Int?) -> ArtifactTicket = { v -> ticket(v ?: latest, "tk_${v ?: latest}") }

        fun ticket(version: Int, name: String) = ArtifactTicket(id, version, "https://artifacts.example.org/a/$id/$name/index.html", null)

        override suspend fun artifacts(accountId: String, before: String?): ArtifactPage = error("not used")
        override suspend fun artifact(accountId: String, artifactId: String): Artifact {
            artifactGate?.await()
            artifactError?.let { throw it }
            return Artifact(artifactId, "Plan", "ada", "team", "owner", latest, "https://app.example.org/artifacts/$artifactId", 1L)
        }
        override suspend fun versions(accountId: String, artifactId: String) = versionList.map { ArtifactVersion(it, it.toLong(), "ada", 1) }
        override suspend fun ticket(accountId: String, artifactId: String, version: Int?): ArtifactTicket { calls += "ticket $version"; return tickets(version) }
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewer(version: Int? = null, embedded: Boolean = true, time: TestTimeSource = TestTimeSource()): ArtifactViewModel {
        val features = FakeFeatures(setOf(Features.ARTIFACTS))
        return ArtifactViewModel(ArtifactRoute("a1", id, version), GetArtifactUseCase(repo, fakeGuard()), OpenArtifactUseCase(repo, features, fakeGuard()), embedded, time)
    }

    @Test
    fun aVersionTheListDoesNotHoldIsGoneUnlessItIsNewerThanEveryOne() = runTest(dispatcher) {
        repo.versionList = listOf(2, 3)
        val missing = viewer(version = 1)
        advanceUntilIdle()
        assertTrue(missing.uiState.value.gone)
        assertNull(missing.uiState.value.opened)
        // Newer than every version listed: announced a moment early, its ticket says.
        val newer = viewer(version = 5)
        advanceUntilIdle()
        assertFalse(newer.uiState.value.gone)
        assertEquals(5, newer.uiState.value.opened?.ticket?.version)
        // No version listed at all: the latest one is shown.
        repo.versionList = emptyList()
        val bare = viewer()
        advanceUntilIdle()
        assertEquals(2, bare.uiState.value.version)
        assertEquals(2, bare.uiState.value.opened?.ticket?.version)
    }

    @Test
    fun aReadAgainKeepsTheTicketOfTheVersionShown() = runTest(dispatcher) {
        val model = viewer()
        advanceUntilIdle()
        model.load()
        advanceUntilIdle()
        assertEquals(listOf("ticket 2"), repo.calls)
        assertFalse(model.uiState.value.loading)
        // Without a web view no ticket is asked at all.
        repo.calls.clear()
        val desktop = viewer(embedded = false)
        advanceUntilIdle()
        assertFalse(desktop.uiState.value.loading)
        assertEquals(2, desktop.uiState.value.version)
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun theWatchOffersANewerVersionAndStopsWhenTheArtifactIsGone() = runTest(dispatcher) {
        repo.versionList = emptyList()
        val gate = CompletableDeferred<Unit>().also { repo.artifactGate = it }
        val model = viewer()
        // Stopping what never started is harmless.
        model.stop()
        runCurrent()
        model.start()
        // The artifact is not read yet: the first round has nothing to compare with.
        advanceTimeBy(30_001); runCurrent()
        assertNull(model.uiState.value.newerVersion)
        gate.complete(Unit)
        advanceTimeBy(30_001); runCurrent()
        // With no versions listed, the artifact's own latest version is the one known: nothing newer yet.
        assertEquals(2, model.uiState.value.artifact?.latestVersion)
        assertNull(model.uiState.value.newerVersion)
        repo.latest = 3
        advanceTimeBy(30_001); runCurrent()
        assertEquals(3, model.uiState.value.newerVersion)
        // Offline for a moment: what is shown stays, the next round asks again.
        repo.artifactError = AppException.Unreachable()
        advanceTimeBy(30_001); runCurrent()
        assertEquals(3, model.uiState.value.newerVersion)
        assertFalse(model.uiState.value.gone)
        repo.artifactError = AppException.NotFound()
        advanceTimeBy(30_001); runCurrent()
        assertTrue(model.uiState.value.gone)
        // A gone artifact is not asked again.
        repo.artifactError = null
        repo.latest = 9
        advanceTimeBy(30_001); runCurrent()
        assertEquals(3, model.uiState.value.newerVersion)
        model.stop()
        model.stop()
    }

    @Test
    fun aVersionIsPickedAgainOnlyWhenItHasNoTicket() = runTest(dispatcher) {
        val model = viewer()
        advanceUntilIdle()
        model.selectVersion(2)
        advanceUntilIdle()
        assertEquals(listOf("ticket 2"), repo.calls)
        // Nothing newer was announced.
        model.showNewer()
        assertEquals(2, model.uiState.value.version)
        // The ticket failed: the same version is asked again.
        repo.tickets = { throw AppException.Unreachable() }
        model.selectVersion(1)
        advanceUntilIdle()
        assertNull(model.uiState.value.opened)
        repo.tickets = { v -> repo.ticket(v ?: 1, "tk_again") }
        model.selectVersion(1)
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.opened?.ticket?.version)
        // Without a web view the version is shown without a ticket, and picked again it does nothing.
        val desktop = viewer(embedded = false)
        advanceUntilIdle()
        desktop.selectVersion(2)
        desktop.selectVersion(1)
        assertEquals(1, desktop.uiState.value.version)
    }

    @Test
    fun aMissingPageNeedsAVersionAnOpenedTicketAndAPageOfThisTicket() = runTest(dispatcher) {
        repo.artifactError = AppException.Unreachable()
        val failed = viewer()
        advanceUntilIdle()
        // No version was ever read: there is nothing to ask a ticket for.
        failed.contentMissing("https://artifacts.example.org/a/$id/tk_2/x.html")
        advanceUntilIdle()
        assertTrue(repo.calls.isEmpty())
        repo.artifactError = null
        repo.tickets = { throw AppException.Unreachable() }
        val noTicket = viewer()
        advanceUntilIdle()
        noTicket.contentMissing("https://artifacts.example.org/a/$id/tk_2/x.html")
        advanceUntilIdle()
        assertEquals(1, repo.calls.size)
        // A page outside the ticket's directory is not asked under another ticket: the entry page is asked again.
        repo.tickets = { v -> repo.ticket(v ?: 2, "tk_2") }
        val model = viewer()
        advanceUntilIdle()
        model.contentMissing("https://elsewhere.example.org/x.html")
        advanceUntilIdle()
        assertTrue(model.uiState.value.missingPage)
        assertNull(model.uiState.value.page)
        // A renewal that says the version is gone is gone, not a missing page.
        repo.tickets = { throw AppException.NotFound() }
        model.contentMissing("https://artifacts.example.org/a/$id/tk_2/y.html")
        advanceUntilIdle()
        assertTrue(model.uiState.value.gone)
    }

    @Test
    fun aTicketThatLandsAfterAnotherVersionWasPickedIsDropped() = runTest(dispatcher) {
        val gates = ArrayDeque<UncancellableGate<ArtifactTicket>>()
        repo.tickets = { UncancellableGate<ArtifactTicket>().also { gates.addLast(it) }.await() }
        val model = viewer()
        advanceUntilIdle()
        val first = gates.removeFirst()
        model.selectVersion(1)
        advanceUntilIdle()
        val second = gates.removeFirst()
        first.release(repo.ticket(2, "tk_old"))
        advanceUntilIdle()
        assertNull(model.uiState.value.opened)
        second.release(repo.ticket(1, "tk_1"))
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.opened?.ticket?.version)
        // A failure of a ticket that is no longer wanted is ignored too, with or without a missing page behind it.
        model.selectVersion(2)
        advanceUntilIdle()
        val stale = gates.removeFirst()
        model.selectVersion(1)
        advanceUntilIdle()
        gates.removeFirst().release(repo.ticket(1, "tk_1"))
        advanceUntilIdle()
        stale.fail(AppException.Unreachable())
        advanceUntilIdle()
        assertNull(model.uiState.value.error)
        model.contentMissing("https://artifacts.example.org/a/$id/tk_1/z.html")
        advanceUntilIdle()
        val renewal = gates.removeFirst()
        model.selectVersion(2)
        advanceUntilIdle()
        gates.removeFirst().release(repo.ticket(2, "tk_2"))
        advanceUntilIdle()
        renewal.fail(AppException.Unreachable())
        advanceUntilIdle()
        assertFalse(model.uiState.value.missingPage)
        assertNull(model.uiState.value.error)
    }

    @Test
    fun theWatchComparesWithTheNewestVersionListed() = runTest(dispatcher) {
        repo.versionList = listOf(1, 2, 3)
        val model = viewer()
        runCurrent()
        model.start()
        model.start()
        advanceTimeBy(30_001); runCurrent()
        assertNull(model.uiState.value.newerVersion, "version 3 is already listed")
        repo.latest = 4
        advanceTimeBy(30_001); runCurrent()
        assertEquals(4, model.uiState.value.newerVersion)
        // Showing it reads the ticket of the newer version, the one shown being another.
        model.showNewer(); runCurrent()
        assertEquals(4, model.uiState.value.opened?.ticket?.version)
        model.stop()
    }

    @Test
    fun aVersionAlreadyShownIsOpenedAgainOnlyWhenItHasNoTicket() = runTest(dispatcher) {
        repo.tickets = { throw AppException.Unreachable() }
        val model = viewer()
        advanceUntilIdle()
        assertNull(model.uiState.value.opened)
        assertEquals(2, model.uiState.value.version)
        repo.tickets = { v -> repo.ticket(v ?: 2, "tk_${v ?: 2}") }
        model.selectVersion(2); advanceUntilIdle()
        assertEquals(2, model.uiState.value.opened?.ticket?.version, "no ticket yet: asked again")
        val asked = repo.calls.size
        model.selectVersion(2); advanceUntilIdle()
        assertEquals(asked, repo.calls.size, "a ticket is there: nothing is asked")
        val desktop = viewer(embedded = false)
        advanceUntilIdle()
        desktop.selectVersion(2); advanceUntilIdle()
        assertEquals(asked, repo.calls.size, "no web view: no ticket either")
    }

    @Test
    fun aTicketThatLandsAfterTheNewerVersionWasShownIsDropped() = runTest(dispatcher) {
        for (scenario in 0..2) {
            repo.latest = 2
            repo.artifactGate = null
            val gates = ArrayDeque<UncancellableGate<ArtifactTicket>>()
            var gated = scenario != 2
            repo.tickets = { v -> if (gated) UncancellableGate<ArtifactTicket>().also { gates.addLast(it) }.await() else repo.ticket(v ?: 2, "tk_${v ?: 2}") }
            val model = viewer()
            runCurrent()
            model.start()
            if (scenario == 2) {
                // A renewal after a missing page is on its way.
                gated = true
                model.contentMissing("https://artifacts.example.org/a/$id/tk_2/z.html"); runCurrent()
            }
            repo.latest = 3
            advanceTimeBy(30_001); runCurrent()
            assertEquals(3, model.uiState.value.newerVersion)
            // The newer version is shown (its artifact read still on its way) while the ticket of the old one lands.
            repo.artifactGate = CompletableDeferred()
            model.showNewer(); runCurrent()
            val inFlight = gates.removeFirst()
            if (scenario == 0) inFlight.release(repo.ticket(2, "late")) else inFlight.fail(AppException.Unreachable())
            runCurrent()
            assertEquals(3, model.uiState.value.version)
            assertFalse(model.uiState.value.missingPage)
            assertNull(model.uiState.value.error)
            if (scenario != 2) assertNull(model.uiState.value.opened)
            model.stop()
        }
    }

    @Test
    fun aCrashedPageReloadsAgainOnceTheMinuteIsOver() = runTest(dispatcher) {
        val time = TestTimeSource()
        val model = viewer(time = time)
        advanceUntilIdle()
        val reload = model.uiState.value.reload
        model.contentCrashed()
        assertEquals(reload + 1, model.uiState.value.reload)
        time += 2.minutes
        model.contentCrashed()
        assertEquals(reload + 2, model.uiState.value.reload)
        model.contentCrashed()
        assertNull(model.uiState.value.opened)
        assertNotNull(model.uiState.value.error)
        // Nothing is shown any more: nothing to reload.
        model.contentCrashed()
        assertEquals(reload + 2, model.uiState.value.reload)
    }
}
