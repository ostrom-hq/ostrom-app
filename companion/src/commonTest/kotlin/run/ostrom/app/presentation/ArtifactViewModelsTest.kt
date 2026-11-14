// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../presentation/ArtifactViewModelsTest.kt; see NOTICE.
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FixedClock
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Artifact
import run.ostrom.app.domain.models.ArtifactPage
import run.ostrom.app.domain.models.ArtifactTicket
import run.ostrom.app.domain.models.ArtifactVersion
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.repositories.ArtifactRepository
import run.ostrom.app.domain.usecases.artifacts.GetArtifactUseCase
import run.ostrom.app.domain.usecases.artifacts.LoadArtifactsUseCase
import run.ostrom.app.domain.usecases.artifacts.OpenArtifactUseCase
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.presentation.navigation.ArtifactRoute
import run.ostrom.app.presentation.viewmodels.ArtifactViewModel
import run.ostrom.app.presentation.viewmodels.ArtifactsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val id = "0b5c7c1e-9f0a-4d2b-8e5f-3a1c2d4e6f80"
    private val accounts = accounts().also { it.save(account()) }
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, MemoryCredentialStore(), FixedClock()))
    private val features = FakeFeatures(setOf(Features.ARTIFACTS))

    private class Fake : ArtifactRepository {
        val calls = mutableListOf<String>()
        var latest = 2
        var listed = 2
        var gone = false
        var pages: (String?) -> ArtifactPage = { ArtifactPage(emptyList(), null) }
        var tickets: (Int?) -> ArtifactTicket = { v -> ArtifactTicket(ID, v ?: latest, "https://artifacts.example.org/a/$ID/tk_${v ?: latest}/index.html", null) }
        var ticketError: Exception? = null

        override suspend fun artifacts(accountId: String, before: String?): ArtifactPage = pages(before).also { calls += "list ${before.orEmpty()}".trim() }
        override suspend fun artifact(accountId: String, artifactId: String): Artifact {
            if (gone) throw AppException.NotFound()
            return Artifact(artifactId, "Plan", "ada", "team", "owner", latest, "https://app.example.org/artifacts/$artifactId", 1L)
        }
        override suspend fun versions(accountId: String, artifactId: String) = (listed downTo 1).map { ArtifactVersion(it, it.toLong(), "ada", 1) }
        override suspend fun ticket(accountId: String, artifactId: String, version: Int?): ArtifactTicket {
            calls += "ticket $version"
            ticketError?.let { throw it }
            return tickets(version)
        }
    }

    private val artifacts = Fake()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewer(version: Int? = null, embedded: Boolean = true) =
        ArtifactViewModel(ArtifactRoute("a1", id, version), GetArtifactUseCase(artifacts, guard), OpenArtifactUseCase(artifacts, features, guard), embedded)

    private fun row(n: Int) = Artifact("id-$n", "Title $n", "ada", "team", null, 1, "", null)

    @Test
    fun theGalleryReadsPageByPageAndARefreshKeepsTheOlderRows() = runTest(dispatcher) {
        artifacts.pages = { before -> if (before == null) ArtifactPage(listOf(row(1)), "c1") else ArtifactPage(listOf(row(1), row(2)), null) }
        val model = ArtifactsViewModel(accounts, LoadArtifactsUseCase(artifacts, features, guard))
        advanceUntilIdle()
        model.start()
        advanceUntilIdle()
        model.loadMore(); model.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("list", "list c1"), artifacts.calls)
        assertEquals(listOf("id-1", "id-2"), model.uiState.value.artifacts.map { it.id })
        model.onQuery("TITLE 2")
        assertEquals(listOf("id-2"), model.uiState.value.shown.map { it.id })
        // Back on the tab: the first page again, the older row kept after it.
        artifacts.pages = { ArtifactPage(listOf(row(3)), "c3") }
        model.start()
        advanceUntilIdle()
        assertEquals(listOf("id-3", "id-1", "id-2"), model.uiState.value.artifacts.map { it.id })
        assertNull(model.uiState.value.next)
        features.features = emptySet()
        model.reload()
        advanceUntilIdle()
        assertNotNull(model.uiState.value.error)
    }

    @Test
    fun theLatestVersionOpensUnderItsTicketAndAnotherIsPicked() = runTest(dispatcher) {
        val model = viewer()
        advanceUntilIdle()
        val state = model.uiState.value
        assertEquals(2, state.version)
        assertEquals("https://artifacts.example.org/a/$id/tk_2/", state.opened?.prefix)
        assertEquals("https://app.example.org/artifacts/$id", state.viewerUrl)
        assertFalse(state.loading)
        model.selectVersion(1)
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.opened?.ticket?.version)
        model.selectVersion(1)
        assertEquals(listOf("ticket 2", "ticket 1"), artifacts.calls)
    }

    @Test
    fun theDesktopAsksNoTicket() = runTest(dispatcher) {
        val model = viewer(embedded = false)
        advanceUntilIdle()
        model.selectVersion(1)
        advanceUntilIdle()
        assertEquals(1, model.uiState.value.version)
        assertNull(model.uiState.value.opened)
        assertTrue(artifacts.calls.isEmpty())
    }

    @Test
    fun aMissingPageIsTheSameTicketAndAnExpiredOneLoadsThePageAgain() = runTest(dispatcher) {
        val model = viewer()
        advanceUntilIdle()
        val prefix = model.uiState.value.opened!!.prefix
        // The same ticket comes back: the link led to a file the version lacks.
        model.contentMissing("${prefix}missing.html")
        advanceUntilIdle()
        assertTrue(model.uiState.value.missingPage)
        model.restart()
        assertFalse(model.uiState.value.missingPage)
        // A renewed ticket: the same page, under it.
        artifacts.tickets = { v -> ArtifactTicket(id, v ?: 2, "https://artifacts.example.org/a/$id/tk_new/index.html", null) }
        model.contentMissing("${prefix}page.html")
        advanceUntilIdle()
        assertEquals("https://artifacts.example.org/a/$id/tk_new/page.html", model.uiState.value.page)
        assertFalse(model.uiState.value.missingPage)
        // A renewal that fails for a passing reason keeps the page shown.
        artifacts.ticketError = AppException.Unreachable()
        model.contentMissing("https://artifacts.example.org/a/$id/tk_new/x.html")
        advanceUntilIdle()
        assertTrue(model.uiState.value.missingPage)
        assertNotNull(model.uiState.value.opened)
        // A refused first ticket says so.
        artifacts.ticketError = AppException.Unsupported(Features.ARTIFACTS)
        val refused = viewer()
        advanceUntilIdle()
        assertNull(refused.uiState.value.opened)
        assertNotNull(refused.uiState.value.error)
    }

    @Test
    fun aCrashedPageReloadsOnceAMinute() = runTest(dispatcher) {
        val model = viewer()
        advanceUntilIdle()
        val reload = model.uiState.value.reload
        model.contentCrashed()
        assertEquals(reload + 1, model.uiState.value.reload)
        model.contentCrashed()
        assertNull(model.uiState.value.opened)
        assertNotNull(model.uiState.value.error)
        model.contentCrashed()
    }

    @Test
    fun aNewerVersionIsOfferedThenShownAndAGoneArtifactSaysSo() = runTest(dispatcher) {
        val model = viewer()
        advanceUntilIdle()
        model.start()
        model.start()
        artifacts.latest = 3
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(3, model.uiState.value.newerVersion)
        // The versions read a moment early: the newer one is shown all the same.
        model.showNewer()
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, model.uiState.value.version)
        assertEquals(3, model.uiState.value.opened?.ticket?.version)
        assertFalse(model.uiState.value.gone)
        artifacts.gone = true
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(model.uiState.value.gone)
        model.stop()
        // A version that does not exist is gone.
        artifacts.gone = false
        val unknown = viewer(version = 0)
        advanceUntilIdle()
        assertTrue(unknown.uiState.value.gone)
    }

    private companion object {
        const val ID = "0b5c7c1e-9f0a-4d2b-8e5f-3a1c2d4e6f80"
    }
}
