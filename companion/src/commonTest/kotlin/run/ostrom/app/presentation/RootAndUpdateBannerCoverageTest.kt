// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import androidx.lifecycle.ViewModelStore
import com.russhwolf.settings.MapSettings
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
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.repositories.UpdatePreferences
import run.ostrom.app.domain.services.CliUpdateException
import run.ostrom.app.domain.services.CliUpdater
import run.ostrom.app.domain.usecases.links.OpenArtifactLinkUseCase
import run.ostrom.app.domain.usecases.push.ResolveNotificationUseCase
import run.ostrom.app.infrastructure.settings.SettingsPreferencesRepository
import run.ostrom.app.presentation.viewmodels.ArtifactLinkOutcome
import run.ostrom.app.presentation.viewmodels.RootViewModel
import run.ostrom.app.presentation.viewmodels.UpdateBannerState
import run.ostrom.app.presentation.viewmodels.UpdateBannerViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RootAndUpdateBannerCoverageTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun aLinkThatIsNotAnArtifactIsRefusedAndANotificationOfNoAccountOpensNothing() = runTest {
        val accounts = accounts().also { it.save(account()) }
        val model = RootViewModel(accounts, SettingsPreferencesRepository(MapSettings()), ResolveNotificationUseCase(accounts), OpenArtifactLinkUseCase(accounts))
        assertTrue(model.hasAccounts.value)
        val outcome = model.openArtifactLink("https://example.org/elsewhere")
        assertTrue(outcome is ArtifactLinkOutcome.Refused)
        assertNull(model.openNotification("not a notification link"))
        assertEquals("a1", accounts.activeAccountId.value)
    }

    private class FakeUpdater : CliUpdater {
        var installed: String? = "0.4.1"
        var update: suspend ((String) -> Unit) -> String? = { output -> output("Downloading"); "0.5.0" }
        override suspend fun installedVersion(): String? = installed
        override suspend fun latestVersion(): String = "0.5.0"
        override suspend fun update(output: (String) -> Unit): String? = update.invoke(output)
    }

    private class MemoryPreferences : UpdatePreferences {
        override var dismissedVersion: String? = null
        override var lastAttemptMillis: Long? = null
        override var latestVersion: String? = null
    }

    private val updater = FakeUpdater()
    private val preferences = MemoryPreferences()
    private val clock = FixedClock()
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

    @Test
    fun aCheckWhileAnUpdateRunsOrEndedLeavesTheBannerAsItIs() = banner {
        val gate = CompletableDeferred<Unit>()
        var lines: ((String) -> Unit)? = null
        updater.update = { output -> lines = output; output("Downloading"); gate.await(); "0.5.0" }
        val model = newViewModel()
        model.update()
        assertTrue(model.uiState.value is UpdateBannerState.Updating)
        model.refresh()
        assertTrue(model.uiState.value is UpdateBannerState.Updating)
        gate.complete(Unit)
        assertEquals(UpdateBannerState.Updated("0.5.0"), model.uiState.value)
        model.refresh()
        assertEquals(UpdateBannerState.Updated("0.5.0"), model.uiState.value)
        // A line that comes after the end is not shown.
        lines!!("late")
        assertEquals(UpdateBannerState.Updated("0.5.0"), model.uiState.value)
    }

    @Test
    fun aFailedUpdateShowsItsOwnLinesOrTheOnesItPrintedAndStaysThroughACheck() = banner {
        updater.update = { output -> output("one"); output("two"); throw CliUpdateException(CliUpdateException.Reason.FAILED) }
        val model = newViewModel()
        model.update()
        assertEquals(UpdateBannerState.Failed("0.5.0", CliUpdateException.Reason.FAILED, listOf("one", "two")), model.uiState.value)
        model.refresh()
        assertEquals(UpdateBannerState.Failed("0.5.0", CliUpdateException.Reason.FAILED, listOf("one", "two")), model.uiState.value)
        // Only the last lines are kept while it runs.
        updater.update = { output -> repeat(9) { output("line $it") }; throw AppException.Unreachable() }
        model.update()
        assertEquals(listOf("line 3", "line 4", "line 5", "line 6", "line 7", "line 8"), (model.uiState.value as UpdateBannerState.Failed).lines)
    }

    @Test
    fun aMissingCliOffersNothingEvenWithAReleaseKnown() = banner {
        updater.installed = null
        val model = newViewModel()
        assertEquals(UpdateBannerState.Hidden, model.uiState.value)
        assertNull(preferences.dismissedVersion)
    }
}
