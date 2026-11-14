// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../UpdateBannerViewModelTest.kt; see NOTICE.
package run.ostrom.app.presentation

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FixedClock
import run.ostrom.app.domain.repositories.UpdatePreferences
import run.ostrom.app.domain.services.CliUpdateException
import run.ostrom.app.domain.services.CliUpdater
import run.ostrom.app.domain.usecases.updates.UpdatePolicy
import run.ostrom.app.presentation.viewmodels.UpdateBannerState
import run.ostrom.app.presentation.viewmodels.UpdateBannerViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateBannerViewModelTest {
    private class FakeUpdater : CliUpdater {
        var installed: String? = "0.4.1"
        var latest: () -> String = { "0.5.0" }
        var checks = 0
        var result: suspend ((String) -> Unit) -> String? = { output -> output("Downloading"); "0.5.0" }

        override suspend fun installedVersion(): String? = installed
        override suspend fun latestVersion(): String {
            checks++
            return latest()
        }
        override suspend fun update(output: (String) -> Unit): String? = result(output)
    }

    private class MemoryPreferences : UpdatePreferences {
        override var dismissedVersion: String? = null
        override var lastAttemptMillis: Long? = null
        override var latestVersion: String? = null
    }

    private val updater = FakeUpdater()
    private val preferences = MemoryPreferences()
    private val clock = FixedClock()

    /** Cleared at the end of each test: their checks repeat forever, which the end of runTest would otherwise follow. */
    private val store = ViewModelStore()
    private var created = 0

    private fun newViewModel() = UpdateBannerViewModel(updater, preferences, clock).also { store.put("banner-${created++}", it) }

    private fun banner(block: suspend TestScope.() -> Unit): TestResult = runTest {
        try {
            block()
        } finally {
            store.clear()
        }
    }

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aNewerReleaseShowsTheBanner() = banner {
        val viewModel = newViewModel()
        assertEquals(UpdateBannerState.Available("0.5.0", "0.4.1"), viewModel.uiState.value)
        assertEquals("0.5.0", preferences.latestVersion)
        assertEquals(clock.now, preferences.lastAttemptMillis)
    }

    @Test
    fun anUpToDateOrMissingCliShowsNothing() = banner {
        updater.latest = { "0.4.1" }
        assertEquals(UpdateBannerState.Hidden, newViewModel().uiState.value)
        preferences.lastAttemptMillis = null
        updater.latest = { "0.5.0" }
        updater.installed = null
        assertEquals(UpdateBannerState.Hidden, newViewModel().uiState.value)
    }

    @Test
    fun aRecentCheckIsNotRepeatedAndAFailureIsSilent() = banner {
        preferences.lastAttemptMillis = clock.now - 1_000
        preferences.latestVersion = "0.5.0"
        assertEquals(UpdateBannerState.Available("0.5.0", "0.4.1"), newViewModel().uiState.value)
        assertEquals(0, updater.checks, "The last result is kept within the hour")
        clock.now += UpdatePolicy.MIN_CHECK_INTERVAL_MILLIS
        updater.latest = { throw CliUpdateException(CliUpdateException.Reason.FAILED) }
        assertEquals(UpdateBannerState.Available("0.5.0", "0.4.1"), newViewModel().uiState.value)
        assertEquals(1, updater.checks)
    }

    @Test
    fun aDismissedVersionStaysHiddenUntilANewerOne() = banner {
        val viewModel = newViewModel()
        viewModel.dismiss()
        assertEquals(UpdateBannerState.Hidden, viewModel.uiState.value)
        assertEquals("0.5.0", preferences.dismissedVersion)
        preferences.lastAttemptMillis = null
        assertEquals(UpdateBannerState.Hidden, newViewModel().uiState.value)
        preferences.lastAttemptMillis = null
        updater.latest = { "0.6.0" }
        assertEquals(UpdateBannerState.Available("0.6.0", "0.4.1"), newViewModel().uiState.value)
    }

    @Test
    fun theUpdateShowsItsLinesThenItsResult() = banner {
        val gate = CompletableDeferred<Unit>()
        updater.result = { output -> output("Downloading ostrom 0.5.0"); gate.await(); "0.5.0" }
        val viewModel = newViewModel()
        viewModel.update()
        assertEquals(UpdateBannerState.Updating("0.5.0", listOf("Downloading ostrom 0.5.0")), viewModel.uiState.value)
        gate.complete(Unit)
        assertEquals(UpdateBannerState.Updated("0.5.0"), viewModel.uiState.value)
        viewModel.dismiss()
        assertEquals(UpdateBannerState.Hidden, viewModel.uiState.value)
        assertEquals(null, preferences.dismissedVersion, "Closing the result dismisses no version")
    }

    @Test
    fun aFailedUpdateSaysWhyAndOffersToTryAgain() = banner {
        updater.result = { output -> output("Downloading"); throw CliUpdateException(CliUpdateException.Reason.FAILED, listOf("✗ The signature does not match")) }
        val viewModel = newViewModel()
        viewModel.update()
        assertEquals(UpdateBannerState.Failed("0.5.0", CliUpdateException.Reason.FAILED, listOf("✗ The signature does not match")), viewModel.uiState.value)
        updater.result = { _ -> throw IllegalStateException("boom") }
        viewModel.update()
        assertEquals(UpdateBannerState.Failed("0.5.0", CliUpdateException.Reason.FAILED), viewModel.uiState.value)
        updater.result = { _ -> null }
        viewModel.update()
        assertEquals(UpdateBannerState.Failed("0.5.0", CliUpdateException.Reason.NOT_INSTALLED), viewModel.uiState.value)
        viewModel.dismiss()
        assertEquals("0.5.0", preferences.dismissedVersion)
    }
}
