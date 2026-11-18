// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FakeTranscriptRepository
import run.ostrom.app.SERVER
import run.ostrom.app.domain.models.ActivityPage
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DeviceLogin
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.KnownAgent
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TranscriptPage
import run.ostrom.app.domain.models.Wait
import run.ostrom.app.domain.models.WaitPage
import run.ostrom.app.domain.models.WaitStatus
import run.ostrom.app.domain.repositories.ActivityRepository
import run.ostrom.app.domain.repositories.BrowserAttempt
import run.ostrom.app.domain.repositories.NewSession
import run.ostrom.app.domain.repositories.TranscriptRepository
import run.ostrom.app.domain.repositories.WaitRepository
import run.ostrom.app.domain.services.BiometricGate
import run.ostrom.app.domain.services.BiometricOutcome
import run.ostrom.app.domain.services.PlatformInfo
import run.ostrom.app.domain.usecases.transcript.ListAgentsUseCase
import run.ostrom.app.domain.usecases.transcript.LoadTranscriptUseCase
import run.ostrom.app.fakeGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the domain's interfaces do when an implementation does not say otherwise, and what its secrets print as. */
class DomainDefaultsTest {
    private class PagesOnly : TranscriptRepository {
        val asked = mutableListOf<Long?>()
        override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage { asked += before; return TranscriptPage(emptyList(), null, 0) }
        override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> = emptyFlow()
    }

    @Test
    fun aRepositoryWithoutFeedsPagesEveryEventAndKnowsNoAgent() = runTest {
        val pages = PagesOnly()
        pages.feedPage("a1", "s1", 42, "main")
        assertEquals(listOf<Long?>(42), pages.asked)
        assertEquals(emptyList(), pages.agents("a1", "s1"))
    }

    @Test
    fun aWaitRepositoryWithoutTimedWaitsListsNone() = runTest {
        val waits = object : WaitRepository {
            override suspend fun list(accountId: String, status: WaitStatus, before: String?) = WaitPage(emptyList(), null)
            override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait = error("unused")
        }
        assertEquals(emptyList(), waits.inProgress("a1"))
    }

    @Test
    fun anActivityPageIsTheNewestWhenNoCursorIsGiven() = runTest {
        val asked = mutableListOf<String?>()
        val activity = object : ActivityRepository {
            override suspend fun page(accountId: String, before: String?): ActivityPage { asked += before; return ActivityPage(emptyList(), null) }
        }
        activity.page("a1")
        assertEquals(listOf<String?>(null), asked)
    }

    @Test
    fun aGateIsSupportedUnlessThePlatformSaysOtherwise() {
        val gate = object : BiometricGate { override suspend fun confirm(prompt: String) = BiometricOutcome.CONFIRMED }
        assertTrue(gate.supported)
    }

    @Test
    fun secretsNeverPrint() {
        assertEquals("NewSession(ada)", NewSession("osm_secret", "ada", null, null, "unlock-secret").toString())
        assertEquals("BrowserAttempt(https://x/start)", BrowserAttempt("https://x/start", "verifier-secret").toString())
        assertEquals("DeviceLogin(${SERVER.origin}, https://github.com/login/device)",
            DeviceLogin(SERVER, "ABCD", "https://github.com/login/device", "device-secret", 5_000, 60_000).toString())
    }

    private fun platform(name: String, model: String) = object : PlatformInfo {
        override val platform = name
        override val deviceModel = model
        override val appVersion = "1.0.0"
    }

    @Test
    fun phonesAreMobileAndComputersAreNot() {
        assertTrue(platform("iOS", "x").isMobile)
        assertTrue(platform("Android", "x").isMobile)
        assertFalse(platform("macOS", "x").isMobile)
    }

    @Test
    fun onlyKnownPlatformsAreReported() {
        assertEquals("windows", platform("Windows", "x").reportedPlatform)
        assertNull(platform("FreeBSD", "x").reportedPlatform)
    }

    @Test
    fun theDefaultDeviceNameDropsControlCharactersAndNeverEndsOnHalfACharacter() {
        assertEquals("Ostrom Linux · box", platform("Linux", "box\u0007").defaultDeviceName)
        // "Ostrom macOS · " is 15 characters: an emoji across the 120th character is dropped whole.
        val model = "x".repeat(104) + "😀"
        assertEquals("Ostrom macOS · " + "x".repeat(104), platform("macOS", model).defaultDeviceName)
        assertEquals(120, platform("macOS", "y".repeat(200)).defaultDeviceName.length)
    }

    // --- The transcript's use cases ---

    @Test
    fun aTranscriptIsReadOnlyFromAServerWithConversations() = runTest {
        val transcripts = FakeTranscriptRepository()
        val load = LoadTranscriptUseCase(transcripts, FakeFeatures(setOf(Features.RUNNERS_CLOUD)), fakeGuard())
        assertFailsWith<AppException.Unsupported> { load("a1", "s1") }
        assertTrue(transcripts.asked.isEmpty())
    }

    @Test
    fun aFeedIsAskedOnlyFromAServerThatPagesThem() = runTest {
        val transcripts = FakeTranscriptRepository()
        val older = LoadTranscriptUseCase(transcripts, FakeFeatures(setOf(Features.RUNNERS_CLOUD_CONVERSATION)), fakeGuard())
        older("a1", "s1", feed = "main")
        assertFalse(older.feeds("a1"))
        val newer = LoadTranscriptUseCase(transcripts, FakeFeatures(setOf(Features.RUNNERS_CLOUD_CONVERSATION, Features.RUNNERS_CLOUD_TRANSCRIPT_FEEDS)), fakeGuard())
        newer("a1", "s1", feed = "main")
        newer("a1", "s1")
        assertTrue(newer.feeds("a1"))
        assertEquals(listOf("page all newest", "page main newest", "page all newest"), transcripts.asked)
    }

    @Test
    fun theAgentsAreListedOnlyByAServerThatTracksThem() = runTest {
        val transcripts = FakeTranscriptRepository().apply { agents = listOf(KnownAgent("t1", "Explore", "Read the code", run.ostrom.app.domain.models.KnownAgentState.RUNNING, 1, null, 0, null, null)) }
        assertEquals(emptyList(), ListAgentsUseCase(transcripts, FakeFeatures(setOf(Features.RUNNERS_CLOUD_CONVERSATION)), fakeGuard())("a1", "s1"))
        assertEquals(listOf("t1"), ListAgentsUseCase(transcripts, FakeFeatures(setOf(Features.RUNNERS_CLOUD_TRANSCRIPT_FEEDS)), fakeGuard())("a1", "s1").map { it.id })
    }
}
