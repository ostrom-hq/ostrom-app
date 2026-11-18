// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeFeatures
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Artifact
import run.ostrom.app.domain.models.ArtifactPage
import run.ostrom.app.domain.models.ArtifactTicket
import run.ostrom.app.domain.models.ArtifactVersion
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.repositories.ArtifactRepository
import run.ostrom.app.domain.usecases.artifacts.LoadArtifactsUseCase
import run.ostrom.app.fakeGuard
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
class ArtifactsViewModelTest {
    private val accounts = accounts().also { it.save(account()) }

    private class Repo : ArtifactRepository {
        val calls = mutableListOf<String>()
        var page: suspend (String?) -> ArtifactPage = { ArtifactPage(emptyList(), null) }
        override suspend fun artifacts(accountId: String, before: String?): ArtifactPage { calls += "$accountId ${before.orEmpty()}".trim(); return page(before) }
        override suspend fun artifact(accountId: String, artifactId: String): Artifact = error("not used")
        override suspend fun versions(accountId: String, artifactId: String): List<ArtifactVersion> = error("not used")
        override suspend fun ticket(accountId: String, artifactId: String, version: Int?): ArtifactTicket = error("not used")
    }

    private val repo = Repo()
    private fun model() = ArtifactsViewModel(accounts, LoadArtifactsUseCase(repo, FakeFeatures(setOf(Features.ARTIFACTS)), fakeGuard()))
    private fun row(id: String) = Artifact(id, "Title $id", "ada", "team", null, 1, "", null)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun theGalleryFollowsTheActiveAccountAndItsSignIn() = runTest {
        repo.page = { ArtifactPage(listOf(row("r1")), null) }
        val model = model()
        accounts.save(account(id = "a2"))
        assertTrue(repo.calls.isEmpty())
        model.start()
        assertEquals(listOf("a2"), repo.calls)
        accounts.update(account(id = "a2", login = "renamed"))
        assertEquals(1, repo.calls.size)
        accounts.setActive("a1")
        assertEquals(2, repo.calls.size)
        accounts.update(account(signedIn = false))
        model.reload()
        model.loadMore()
        assertEquals(2, repo.calls.size)
        accounts.remove("a1")
        accounts.remove("a2")
        assertNull(model.uiState.value.account)
        model.reload()
        model.loadMore()
    }

    @Test
    fun aNextPageIsAskedOnceAndOnlyWhenThereIsOne() = runTest {
        val gate = UncancellableGate<ArtifactPage>()
        repo.page = { before -> if (before == null) ArtifactPage(listOf(row("r1")), "c2") else gate.await() }
        val model = model()
        model.loadMore()
        model.start()
        model.loadMore()
        assertTrue(model.uiState.value.loadingMore)
        // The request already running is not doubled.
        model.loadMore()
        assertEquals(listOf("a1", "a1 c2"), repo.calls)
        gate.release(ArtifactPage(listOf(row("r1"), row("r2")), null))
        assertEquals(listOf("r1", "r2"), model.uiState.value.artifacts.map { it.id })
        model.loadMore()
        assertEquals(2, repo.calls.size)
    }

    @Test
    fun aPageThatLandsAfterTheAccountChangedIsDropped() = runTest {
        val gates = ArrayDeque<UncancellableGate<ArtifactPage>>()
        repo.page = { UncancellableGate<ArtifactPage>().also { gates.addLast(it) }.await() }
        val model = model()
        for (failure in listOf(false, true)) {
            accounts.setActive("a1")
            gates.clear()
            model.reload()
            val inFlight = gates.removeFirst()
            accounts.save(account(id = "b$failure"))
            if (failure) inFlight.fail(AppException.Unreachable()) else inFlight.release(ArtifactPage(listOf(row("late")), null))
            assertEquals("b$failure", model.uiState.value.account?.id)
            assertTrue(model.uiState.value.artifacts.isEmpty())
            assertNull(model.uiState.value.error)
        }
        // Back on the account, a failure is shown.
        accounts.setActive("a1")
        gates.clear()
        model.reload()
        gates.removeFirst().fail(AppException.Unreachable())
        assertNotNull(model.uiState.value.error)
        assertFalse(model.uiState.value.loading)
    }

    @Test
    fun aPageThatLandsAfterTheAccountWasRemovedIsDropped() = runTest {
        val gates = ArrayDeque<UncancellableGate<ArtifactPage>>()
        repo.page = { UncancellableGate<ArtifactPage>().also { gates.addLast(it) }.await() }
        val model = model()
        for (failure in listOf(false, true)) {
            accounts.save(account())
            accounts.setActive("a1")
            gates.clear()
            model.reload()
            val inFlight = gates.removeFirst()
            accounts.remove("a1")
            if (failure) inFlight.fail(AppException.Unreachable()) else inFlight.release(ArtifactPage(listOf(row("late")), null))
            assertNull(model.uiState.value.account)
            assertTrue(model.uiState.value.artifacts.isEmpty())
            assertNull(model.uiState.value.error)
        }
    }
}
