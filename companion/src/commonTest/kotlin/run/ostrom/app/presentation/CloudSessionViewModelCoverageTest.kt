// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.ConversationCalls
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeFileRepository
import run.ostrom.app.FixedClock
import run.ostrom.app.SequenceIds
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.cloudSession
import run.ostrom.app.domain.models.AgentQuestion
import run.ostrom.app.domain.models.AgentQuestionOption
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionMode
import run.ostrom.app.domain.models.CloudSessionPage
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.FileProblem
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.InputReceipt
import run.ostrom.app.domain.models.KnownAgent
import run.ostrom.app.domain.models.KnownAgentState
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.models.PendingFile
import run.ostrom.app.domain.models.PendingMessage
import run.ostrom.app.domain.models.PermissionAnswer
import run.ostrom.app.domain.models.QuestionChoice
import run.ostrom.app.domain.models.ServerCapabilities
import run.ostrom.app.domain.models.SessionKind
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TranscriptPage
import run.ostrom.app.domain.models.TurnPhase
import run.ostrom.app.domain.models.UploadedFile
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.TranscriptRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.domain.usecases.files.SessionFilesUseCase
import run.ostrom.app.domain.usecases.sessions.AnswerPermissionUseCase
import run.ostrom.app.domain.usecases.sessions.CloudSessionAccess
import run.ostrom.app.domain.usecases.sessions.ControlCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.ControlConversationUseCase
import run.ostrom.app.domain.usecases.sessions.ConversationOptions
import run.ostrom.app.domain.usecases.sessions.GetCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionOptionsUseCase
import run.ostrom.app.domain.usecases.sessions.SendMessageUseCase
import run.ostrom.app.domain.usecases.sessions.SetCloudSessionGitHubAccessUseCase
import run.ostrom.app.domain.usecases.transcript.FollowTranscriptUseCase
import run.ostrom.app.domain.usecases.transcript.ListAgentsUseCase
import run.ostrom.app.domain.usecases.transcript.LoadTranscriptUseCase
import run.ostrom.app.domain.usecases.transcript.Transcript
import run.ostrom.app.fakeGuard
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.picked
import run.ostrom.app.policy
import run.ostrom.app.presentation.navigation.CloudSessionRoute
import run.ostrom.app.presentation.viewmodels.AnswerState
import run.ostrom.app.presentation.viewmodels.CloudAction
import run.ostrom.app.presentation.viewmodels.CloudConversationUseCases
import run.ostrom.app.presentation.viewmodels.CloudSessionUiState
import run.ostrom.app.presentation.viewmodels.CloudSessionViewModel
import run.ostrom.app.presentation.viewmodels.LiveState
import run.ostrom.app.presentation.viewmodels.PermissionCard
import run.ostrom.app.presentation.viewmodels.PermissionDraft
import run.ostrom.app.presentation.viewmodels.SessionNotice
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A cloud session repository whose reads and writes a test can hold, or make fail. */
private class CoverageSessionRepo(var session: CloudSession) : ConversationCalls() {
    var sessionHold: suspend () -> Unit = {}
    var sessionFailure: Throwable? = null
    var pauseHold: suspend () -> Unit = {}
    var githubHold: suspend () -> Unit = {}
    var githubFailure: Throwable? = null

    override fun current(sessionId: String) = session
    override fun changed(session: CloudSession): CloudSession { this.session = session; return session }
    override suspend fun sessions(accountId: String, before: String?, kinds: Set<SessionKind>) = CloudSessionPage(listOf(session), null)

    override suspend fun session(accountId: String, sessionId: String): CloudSession {
        calls += "get $sessionId"
        // What the server held when the read started, whatever happens before the answer reaches the screen.
        val read = session
        sessionHold()
        sessionFailure?.let { throw it }
        return read
    }

    override suspend fun create(accountId: String, session: NewCloudSession, idempotencyKey: String) = this.session

    override suspend fun pause(accountId: String, sessionId: String): CloudSession {
        calls += "pause $sessionId"
        pauseHold()
        return changed(session.copy(status = CloudSessionStatus.PAUSING))
    }

    override suspend fun resume(accountId: String, sessionId: String) = session
    override suspend fun stop(accountId: String, sessionId: String) = session

    override suspend fun setGitHubAccess(accountId: String, sessionId: String, access: GitHubAccess): CloudSession {
        calls += "github ${access.wire}"
        githubHold()
        githubFailure?.let { throw it }
        return changed(session.copy(githubAccess = access))
    }
}

/** A transcript a test sets, with holds on its pages and on the list of subagents, and a stream a test feeds. */
private class CoverageTranscripts : TranscriptRepository {
    var live = Channel<LiveUpdate<TranscriptEvent>>(Channel.UNLIMITED)
    val pages = mutableMapOf<String?, TranscriptPage>()
    val olderPages = mutableMapOf<String?, TranscriptPage>()
    var agents: List<KnownAgent> = emptyList()
    var agentsHold: suspend () -> Unit = {}
    var pageHold: suspend () -> Unit = {}
    var pageFailure: Throwable? = null
    val asked = mutableListOf<String>()

    override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage = feedPage(accountId, sessionId, before, null)

    override suspend fun feedPage(accountId: String, sessionId: String, before: Long?, feed: String?): TranscriptPage {
        asked += "page ${feed ?: "all"} ${before ?: "newest"}"
        pageHold()
        pageFailure?.let { throw it }
        return (if (before == null) pages[feed] else olderPages[feed]) ?: TranscriptPage(emptyList(), null, 0)
    }

    override suspend fun agents(accountId: String, sessionId: String): List<KnownAgent> {
        asked += "agents"
        agentsHold()
        return agents
    }

    override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> {
        asked += "follow $after"
        return live.receiveAsFlow()
    }
}

/** Capabilities that fail on the [failOn]th read. */
private class CoverageFlakyFeatures(var features: Set<String>) : FeaturesRepository {
    var reads = 0
    var failOn = -1

    override suspend fun capabilities(accountId: String, refresh: Boolean): ServerCapabilities {
        if (++reads == failOn) throw AppException.Unreachable()
        return ServerCapabilities("1.4.0", 1, features)
    }

    override fun cached(accountId: String) = ServerCapabilities("1.4.0", 1, features)
}

/** What the cloud session screen's model does at its edges: races, guards, failures and every kind of event. */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudSessionViewModelCoverageTest {
    private val dispatcher = StandardTestDispatcher()
    private val talk = cloudSession("s1", ownerId = "m1").copy(mode = CloudSessionMode.CONVERSATION)
    private val repo = CoverageSessionRepo(talk)
    private val transcripts = CoverageTranscripts()
    private val features = CoverageFlakyFeatures(
        setOf(
            Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_CONVERSATION, Features.RUNNERS_CLOUD_PERMISSIONS,
            Features.RUNNERS_CLOUD_PERMISSIONS_BYPASS, Features.RUNNERS_CLOUD_TASKS, Features.RUNNERS_CLOUD_MODEL, Features.RUNNERS_CLOUD_EFFORT,
        ),
    )
    private var seq = 10L

    @BeforeTest fun setUp() {
        Dispatchers.setMain(dispatcher)
        firstPage(TranscriptPage(listOf(TranscriptEvent.AgentMessage(4, 4, "Hello", null)), nextBefore = 4, lastSeq = 4))
    }

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun firstPage(page: TranscriptPage) {
        transcripts.pages[null] = page
        transcripts.pages["main"] = page
    }

    private fun model(
        memberId: String? = "m1", withAccount: Boolean = true, conversation: Boolean = true, files: SessionFilesUseCase? = null,
    ): CloudSessionViewModel {
        val accounts = accounts().also { if (withAccount) it.save(account("a1", memberId = memberId)) }
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, MemoryCredentialStore(), FixedClock()))
        val access = CloudSessionAccess(repo, features, guard)
        val uses = if (!conversation) null else CloudConversationUseCases(
            LoadTranscriptUseCase(transcripts, features, guard), FollowTranscriptUseCase(transcripts, guard), ListAgentsUseCase(transcripts, features, guard),
            SendMessageUseCase(repo, features, guard, SequenceIds()), ControlConversationUseCase(repo, guard, features),
            AnswerPermissionUseCase(repo, features, guard, SequenceIds()), accounts, files,
        )
        return CloudSessionViewModel(
            CloudSessionRoute("a1", repo.session.id), GetCloudSessionUseCase(access), ControlCloudSessionUseCase(access),
            LoadCloudSessionOptionsUseCase(access), SetCloudSessionGitHubAccessUseCase(access), uses,
        )
    }

    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun TestScope.push(vararg events: TranscriptEvent) {
        events.forEach { transcripts.live.trySend(LiveUpdate.Item(it)) }
        settle()
    }

    private fun gets() = repo.calls.count { it == "get s1" }
    private fun follows() = transcripts.asked.count { it.startsWith("follow") }
    private fun agentReads() = transcripts.asked.count { it == "agents" }
    private fun CloudSessionViewModel.choice(question: Int) = uiState.value.permission?.draft?.choices?.get(question)
    private fun next() = seq++

    private fun options(vararg labels: String) = labels.map { AgentQuestionOption(it, "About $it") }
    private val colour = AgentQuestion(0, "Red or blue?", "Colour", options("Red", "Blue"), multiSelect = false)
    private val sizes = AgentQuestion(1, "Which sizes?", null, options("S", "M", "L"), multiSelect = true)
    private val name = AgentQuestion(2, "Its name?", null, options("Ada"), multiSelect = false)
    private fun ask(id: String = "r1", questions: List<AgentQuestion> = listOf(colour, sizes, name)) =
        TranscriptEvent.PermissionRequest(next(), 0, id, "AskUserQuestion", true, questions, input = "{}")
    private fun tool(id: String, leftOut: Boolean = false) = TranscriptEvent.PermissionRequest(
        next(), 0, id, "Bash", false, input = if (leftOut) null else "{\"command\": \"ls\"}", inputLeftOut = leftOut,
    )
    private fun agent(id: String) = KnownAgent(id, "general", "Looks around", KnownAgentState.RUNNING, 1L, null, 1, null, null)

    // ---- Derived state ------------------------------------------------------------------------------------------

    @Test
    fun theDerivedStateAnswersForEveryShapeOfSession() {
        val owner = talk.copy(githubAccess = GitHubAccess.OWNER)
        assertFalse(CloudSessionUiState(githubAccessOffered = true).canChooseAccess)
        assertFalse(CloudSessionUiState(githubAccessOffered = false, session = owner).canChooseAccess)
        assertFalse(CloudSessionUiState(githubAccessOffered = true, session = talk).canChooseAccess)
        assertFalse(CloudSessionUiState(githubAccessOffered = true, session = owner.copy(status = CloudSessionStatus.TERMINATED)).canChooseAccess)
        assertTrue(CloudSessionUiState(githubAccessOffered = true, session = owner).canChooseAccess)

        val talks = ConversationOptions(conversation = true)
        assertFalse(CloudSessionUiState(session = talk).conversation)
        assertFalse(CloudSessionUiState(options = talks).conversation)
        assertFalse(CloudSessionUiState(options = talks, session = talk.copy(mode = CloudSessionMode.TERMINAL)).conversation)
        assertTrue(CloudSessionUiState(options = talks, session = talk).conversation)

        val feeds = CloudSessionUiState(
            options = ConversationOptions(feeds = true), loaded = true, loadingOlder = true, loadingFeeds = setOf("a1"),
            transcript = Transcript(nextBefore = 7, feeds = mapOf("a1" to 3L)),
        )
        assertEquals(7L, feeds.olderOf(null))
        assertEquals(3L, feeds.olderOf("a1"))
        assertNull(feeds.olderOf("zz"))
        assertTrue(feeds.loadingOlderOf(null))
        assertTrue(feeds.loadingOlderOf("a1"))
        assertFalse(feeds.loadingOlderOf("a2"))
        assertTrue(feeds.threadLoaded(null))
        assertTrue(feeds.threadLoaded("a1"))
        assertFalse(feeds.threadLoaded("a2"))
        assertFalse(feeds.copy(loaded = false).threadLoaded(null))
        val plain = feeds.copy(options = ConversationOptions())
        assertTrue(plain.loadingOlderOf("a2"))
        assertEquals(7L, plain.olderOf("a1"))
        assertTrue(plain.threadLoaded("a2"))

        assertFalse(CloudSessionUiState().agentRuns)
        assertTrue(CloudSessionUiState(session = talk).agentRuns)
        assertFalse(CloudSessionUiState(session = talk.copy(status = CloudSessionStatus.PAUSED)).agentRuns)

        val perms = ConversationOptions(permissions = true)
        val asked = Transcript(listOf(TranscriptEvent.PermissionRequest(1, 1, "t1", "Bash", false)))
        assertEquals(1, CloudSessionUiState(options = perms, transcript = asked).openRequests.size, "without a session the requests show")
        assertEquals(1, CloudSessionUiState(options = perms, session = talk, transcript = asked).openRequests.size)
        assertTrue(CloudSessionUiState(transcript = asked).openRequests.isEmpty())
        assertTrue(CloudSessionUiState(options = perms, session = talk.copy(status = CloudSessionStatus.PAUSED), transcript = asked).openRequests.isEmpty())

        val bypass = CloudSessionUiState(options = ConversationOptions(bypass = true), canControl = true, session = talk)
        assertTrue(bypass.bypassOffered)
        assertFalse(bypass.copy(options = ConversationOptions()).bypassOffered)
        assertFalse(bypass.copy(canControl = false).bypassOffered)
        assertFalse(bypass.copy(session = null).bypassOffered)
        assertFalse(bypass.copy(session = talk.copy(permissionBypass = true)).bypassOffered)

        val started = Transcript(listOf(TranscriptEvent.Turn(5, 5, TurnPhase.STARTED, null, null)))
        assertTrue(CloudSessionUiState(transcript = started).running, "a turn started before the record was read")
    }

    @Test
    fun aPermissionCardNamesItsRequestAndKnowsWhenTheCurrentQuestionIsAnswered() {
        val unnamed = PermissionCard(TranscriptEvent.PermissionRequest(1, 1, null, "Bash", false), PermissionDraft(), 0)
        assertEquals("", unnamed.requestId)
        assertFalse(unnamed.currentAnswered, "a request without a question has none to answer")
        val question = PermissionCard(TranscriptEvent.PermissionRequest(1, 1, "r1", "AskUserQuestion", true, listOf(colour)), PermissionDraft(), 0)
        assertFalse(question.currentAnswered)
        assertTrue(question.copy(draft = PermissionDraft(choices = mapOf(0 to QuestionChoice(setOf(1))))).currentAnswered)
    }

    // ---- Reading the record -------------------------------------------------------------------------------------

    @Test
    fun aFailedOptionsProbeHidesTheChoiceButTheRecordLoads() = runTest(dispatcher) {
        features.features += Features.RUNNERS_CLOUD_GITHUB
        features.failOn = 2
        val model = model()
        model.load(); settle()
        assertEquals("s1", model.uiState.value.session?.id)
        assertFalse(model.uiState.value.githubAccessOffered)
        assertNull(model.uiState.value.error)
    }

    @Test
    fun aMissingSessionIsGoneAndAnyOtherFailureIsShown() = runTest(dispatcher) {
        repo.sessionFailure = AppException.NotFound()
        val missing = model()
        missing.load(); settle()
        assertTrue(missing.uiState.value.gone)
        assertNotNull(missing.uiState.value.error)
        assertFalse(missing.uiState.value.loading)
        repo.sessionFailure = AppException.Unreachable()
        val offline = model()
        offline.load(); settle()
        assertFalse(offline.uiState.value.gone)
        assertNotNull(offline.uiState.value.error)
        assertFalse(offline.uiState.value.loading)
    }

    @Test
    fun readingTheRecordWithoutBeingVisibleDoesNotFollowTheConversation() = runTest(dispatcher) {
        val model = model()
        model.load(); settle()
        assertTrue(model.uiState.value.conversation)
        assertFalse(model.uiState.value.loaded)
        assertTrue(transcripts.asked.isEmpty())
    }

    @Test
    fun withoutConversationUseCasesTheRecordShowsAsATerminalSessionAndEveryConversationActionDoesNothing() = runTest(dispatcher) {
        val model = model(conversation = false)
        model.retryTranscript()
        model.visible(); settle()
        assertFalse(model.uiState.value.conversation)
        assertNull(model.uiState.value.memberId)
        assertFalse(model.uiState.value.canControl)
        model.retryTranscript(); settle()
        model.loadOlder(); model.openThread("a1"); model.onDraftChange("hi"); model.send()
        model.interrupt(); model.setModel("claude-x"); model.setEffort("high"); model.revokeBypass()
        model.askStop("b1"); model.confirmStop()
        model.attach(listOf(picked("a.png"))); model.explainFiles(); model.retryFile("x"); model.removeFile("x"); model.dismissFileNotice()
        settle()
        assertEquals("b1", model.uiState.value.confirmStop, "nothing confirms a stop here")
        assertEquals("hi", model.uiState.value.draft)
        assertEquals("a1", model.uiState.value.thread)
        assertTrue(model.uiState.value.pending.isEmpty())
        assertFalse(model.uiState.value.busy)
        assertTrue(transcripts.asked.isEmpty())
        assertEquals(listOf("get s1"), repo.calls)
        model.hidden()
    }

    @Test
    fun aMemberTheDeviceDoesNotKnowOwnsNothing() = runTest(dispatcher) {
        val model = model(withAccount = false)
        model.visible(); settle()
        assertNull(model.uiState.value.memberId)
        assertFalse(model.uiState.value.canControl)
        model.hidden()
    }

    @Test
    fun aReadThatStartedBeforeAnActionsAnswerDoesNotOverwriteIt() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        val gate = CompletableDeferred<Unit>()
        repo.sessionHold = { gate.await() }
        model.load(); settle()
        model.setModel("claude-x"); settle()
        assertEquals("claude-x", model.uiState.value.session?.model)
        gate.complete(Unit); settle()
        assertEquals("claude-x", model.uiState.value.session?.model, "the older read keeps the action's session")
        assertFalse(model.uiState.value.loading)
        model.hidden()
    }

    @Test
    fun aHeaderReadThatStartedBeforeAnActionsAnswerIsDropped() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        val gate = CompletableDeferred<Unit>()
        repo.sessionHold = { gate.await() }
        advanceTimeBy(10_000); runCurrent()
        model.setModel("claude-x"); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals("claude-x", model.uiState.value.session?.model)
        model.hidden()
    }

    // ---- Actions and the GitHub choice ----------------------------------------------------------------------------

    private fun githubModel(): CloudSessionViewModel {
        features.features += Features.RUNNERS_CLOUD_GITHUB
        repo.session = talk.copy(githubAccess = GitHubAccess.OWNER)
        return model()
    }

    @Test
    fun theGitHubChoiceAndTheActionsWaitForTheRecordAndForEachOther() = runTest(dispatcher) {
        val model = githubModel()
        model.chooseGitHubAccess(GitHubAccess.SHARED); model.pause(); settle()
        assertTrue(repo.calls.none { it.startsWith("github") || it.startsWith("pause") }, "no record, nothing to act on")
        model.load(); settle()
        assertTrue(model.uiState.value.canChooseAccess)

        val github = CompletableDeferred<Unit>()
        repo.githubHold = { github.await() }
        model.chooseGitHubAccess(GitHubAccess.SHARED); settle()
        assertEquals(GitHubAccess.SHARED, model.uiState.value.settingAccess)
        model.chooseGitHubAccess(GitHubAccess.NONE); model.pause(); settle()
        assertEquals(listOf("github shared"), repo.calls.filter { it.startsWith("github") || it.startsWith("pause") })
        github.complete(Unit); settle()
        assertNull(model.uiState.value.settingAccess)
        assertEquals(GitHubAccess.SHARED, model.uiState.value.session?.githubAccess)

        val pause = CompletableDeferred<Unit>()
        repo.pauseHold = { pause.await() }
        model.pause(); settle()
        assertEquals(CloudAction.PAUSE, model.uiState.value.acting)
        model.chooseGitHubAccess(GitHubAccess.NONE); model.pause(); settle()
        assertEquals(1, repo.calls.count { it.startsWith("pause") })
        assertEquals(1, repo.calls.count { it.startsWith("github") })
        pause.complete(Unit); settle()
        assertNull(model.uiState.value.acting)
    }

    @Test
    fun theGitHubChoiceNeedsTheServerToOfferItAndAChangeToMake() = runTest(dispatcher) {
        val model = model()
        model.load(); settle()
        assertFalse(model.uiState.value.canChooseAccess)
        model.chooseGitHubAccess(GitHubAccess.SHARED); settle()
        assertTrue(repo.calls.none { it.startsWith("github") })
        val offered = githubModel()
        offered.load(); settle()
        offered.chooseGitHubAccess(GitHubAccess.OWNER); settle()
        assertTrue(repo.calls.none { it.startsWith("github") }, "the current access is not set again")
    }

    @Test
    fun aCancelledGitHubChoiceLeavesNothingPending() = runTest(dispatcher) {
        val model = githubModel()
        model.load(); settle()
        repo.githubFailure = CancellationException("cancelled")
        model.chooseGitHubAccess(GitHubAccess.SHARED); settle()
        assertNull(model.uiState.value.settingAccess)
        assertNull(model.uiState.value.actionError)
        assertEquals(GitHubAccess.OWNER, model.uiState.value.session?.githubAccess)
    }

    // ---- The stream and the header --------------------------------------------------------------------------------

    @Test
    fun anEndedSessionIsNotReadByTheHeader() = runTest(dispatcher) {
        repo.session = talk.copy(status = CloudSessionStatus.TERMINATED)
        val model = model()
        model.visible(); settle()
        advanceTimeBy(25_000); runCurrent()
        assertEquals(1, gets())
        model.hidden()
    }

    @Test
    fun aFailedStreamStopsAndRetryFollowsAgain() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        transcripts.live.close(AppException.Unreachable()); settle()
        assertEquals(LiveState.STOPPED, model.uiState.value.live)
        assertNotNull(model.uiState.value.transcriptError)
        assertFalse(model.uiState.value.gone)
        transcripts.live = Channel(Channel.UNLIMITED)
        model.retryTranscript(); settle()
        assertNull(model.uiState.value.transcriptError)
        assertEquals(LiveState.CONNECTING, model.uiState.value.live)
        assertEquals(2, follows())
        transcripts.live.trySend(LiveUpdate.Opened(true)); settle()
        assertEquals(LiveState.LIVE, model.uiState.value.live)
        model.hidden()
    }

    @Test
    fun aStreamThatCannotFindTheSessionMarksItGoneAndNothingFollowsAgain() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        val reads = gets()
        transcripts.live.close(AppException.NotFound()); settle()
        assertTrue(model.uiState.value.gone)
        assertEquals(LiveState.STOPPED, model.uiState.value.live)
        model.retryTranscript(); settle()
        assertEquals(1, follows(), "a session that is gone is not followed again")
        advanceTimeBy(25_000); runCurrent()
        assertEquals(reads, gets(), "nor is its header read")
        model.hidden()
    }

    @Test
    fun aStreamFailureAfterTheRecordWentMissingKeepsItGone() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        repo.sessionFailure = AppException.NotFound()
        model.load(); settle()
        assertTrue(model.uiState.value.gone)
        transcripts.live.close(AppException.Unreachable()); settle()
        assertTrue(model.uiState.value.gone)
        assertEquals(LiveState.STOPPED, model.uiState.value.live)
        model.hidden()
    }

    @Test
    fun anEndedStreamIsNotFollowedAgain() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        transcripts.live.trySend(LiveUpdate.Ended); transcripts.live.close(); settle()
        assertEquals(LiveState.ENDED, model.uiState.value.live)
        model.retryTranscript(); settle()
        assertEquals(1, follows())
        assertEquals(LiveState.ENDED, model.uiState.value.live)
        model.hidden()
    }

    @Test
    fun retryWhileFollowingAsksForTheOlderPageAndLoadingAgainFollowsOnce() = runTest(dispatcher) {
        transcripts.olderPages[null] = TranscriptPage(listOf(TranscriptEvent.AgentMessage(2, 2, "Earlier", null)), nextBefore = null, lastSeq = 4)
        val model = model()
        model.visible(); settle()
        model.load(); settle()
        assertEquals(1, follows())
        model.retryTranscript(); settle()
        assertTrue("page all 4" in transcripts.asked)
        assertEquals(listOf(2L, 4L), model.uiState.value.transcript.events.map { it.seq })
        assertNull(model.uiState.value.transcript.nextBefore)
        model.hidden()
    }

    @Test
    fun aReconnectionReadsTheHeaderAndTheSubagentsAgainAndAGapChangesNothing() = runTest(dispatcher) {
        features.features += Features.RUNNERS_CLOUD_TRANSCRIPT_FEEDS
        val model = model()
        model.visible(); settle()
        transcripts.live.trySend(LiveUpdate.Opened(false)); settle()
        val reads = gets()
        val agents = agentReads()
        transcripts.live.trySend(LiveUpdate.Reconnecting(500)); settle()
        assertEquals(LiveState.RECONNECTING, model.uiState.value.live)
        transcripts.live.trySend(LiveUpdate.Gap); settle()
        assertEquals(LiveState.RECONNECTING, model.uiState.value.live)
        transcripts.live.trySend(LiveUpdate.Opened(true)); settle()
        assertEquals(LiveState.LIVE, model.uiState.value.live)
        assertEquals(reads + 1, gets())
        assertEquals(agents + 1, agentReads())
        model.hidden()
    }

    // ---- Older pages and subagent threads -------------------------------------------------------------------------

    @Test
    fun olderEventsLoadOncePerPageAndNothingOlderAsksNothing() = runTest(dispatcher) {
        transcripts.olderPages[null] = TranscriptPage(listOf(TranscriptEvent.AgentMessage(2, 2, "Earlier", null)), nextBefore = 2, lastSeq = 4)
        val model = model()
        model.visible(); settle()
        val gate = CompletableDeferred<Unit>()
        transcripts.pageHold = { gate.await() }
        model.loadOlder(); model.loadOlder(); settle()
        assertTrue(model.uiState.value.loadingOlder)
        assertEquals(1, transcripts.asked.count { it == "page all 4" })
        gate.complete(Unit); settle()
        assertFalse(model.uiState.value.loadingOlder)
        assertEquals(listOf(2L, 4L), model.uiState.value.transcript.events.map { it.seq })
        assertEquals(2L, model.uiState.value.transcript.nextBefore)
        model.hidden()

        firstPage(TranscriptPage(listOf(TranscriptEvent.AgentMessage(4, 4, "Hello", null)), nextBefore = null, lastSeq = 4))
        val whole = model()
        whole.visible(); settle()
        val asks = transcripts.asked.size
        whole.loadOlder(); settle()
        assertEquals(asks, transcripts.asked.size)
        whole.hidden()
    }

    @Test
    fun aFailedOlderPageIsShownAndCanBeAskedAgain() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        transcripts.pageFailure = AppException.Unreachable()
        model.loadOlder(); settle()
        assertNotNull(model.uiState.value.transcriptError)
        assertFalse(model.uiState.value.loadingOlder)
        model.hidden()
    }

    @Test
    fun aSubagentsThreadPagesApartFromTheMainOne() = runTest(dispatcher) {
        features.features += Features.RUNNERS_CLOUD_TRANSCRIPT_FEEDS
        transcripts.pages["a1"] = TranscriptPage(listOf(TranscriptEvent.AgentMessage(10, 10, "Sub", "a1")), nextBefore = 9, lastSeq = 10)
        transcripts.olderPages["a1"] = TranscriptPage(listOf(TranscriptEvent.AgentMessage(8, 8, "Earlier sub", "a1")), nextBefore = null, lastSeq = 10)
        val model = model()
        model.visible(); settle()
        assertTrue("page main newest" in transcripts.asked)
        model.openThread("a1")
        assertEquals(setOf("a1"), model.uiState.value.loadingFeeds)
        settle()
        assertTrue(model.uiState.value.loadingFeeds.isEmpty())
        assertEquals(mapOf<String, Long?>("a1" to 9L), model.uiState.value.transcript.feeds)
        model.loadOlder(); settle()
        assertTrue("page a1 9" in transcripts.asked)
        assertEquals(mapOf<String, Long?>("a1" to null), model.uiState.value.transcript.feeds)
        val asks = transcripts.asked.size
        model.loadOlder(); settle()
        assertEquals(asks, transcripts.asked.size, "everything of the thread was loaded")
        transcripts.pageFailure = AppException.Unreachable()
        model.openThread("a2"); settle()
        assertNotNull(model.uiState.value.transcriptError)
        assertTrue(model.uiState.value.loadingFeeds.isEmpty())
        model.hidden()
    }

    // ---- The server's list of subagents ---------------------------------------------------------------------------

    @Test
    fun onlyTheEventsThatMayChangeTheSubagentsReadThemAgain() = runTest(dispatcher) {
        features.features += Features.RUNNERS_CLOUD_TRANSCRIPT_FEEDS
        transcripts.agents = listOf(agent("ag1"), agent("ag2"))
        val model = model()
        model.visible(); settle()
        assertEquals(listOf("ag1", "ag2"), model.uiState.value.agents.map { it.id })

        fun reads(event: TranscriptEvent): Boolean {
            val before = agentReads()
            push(event)
            return agentReads() > before
        }
        assertFalse(reads(TranscriptEvent.AgentMessage(next(), 0, "hi", null)))
        assertTrue(reads(TranscriptEvent.ToolCall(next(), 0, "tc1", "Agent", "{}", null, null)))
        assertFalse(reads(TranscriptEvent.ToolCall(next(), 0, "tc2", "Bash", "{}", null, null)))
        assertFalse(reads(TranscriptEvent.ToolCall(next(), 0, "tc3", "Bash", "{}", null, "zzz")))
        assertTrue(reads(TranscriptEvent.ToolCall(next(), 0, "tc4", "Bash", "{}", null, "ag1")))
        assertTrue(reads(TranscriptEvent.ToolResult(next(), 0, "ag2", "ok", false, null, null)))
        assertFalse(reads(TranscriptEvent.ToolResult(next(), 0, "tc2", "ok", false, null, null)))
        assertFalse(reads(TranscriptEvent.ToolResult(next(), 0, "tc9", "ok", false, null, null)))
        assertTrue(reads(TranscriptEvent.ToolResult(next(), 0, "tc1", "ok", false, null, null)))
        assertFalse(reads(TranscriptEvent.ToolResult(next(), 0, "tcx", "ok", false, null, "p1")))
        assertTrue(reads(TranscriptEvent.Tasks(next(), 0, emptyList(), stoppable = true)))
        assertTrue(reads(TranscriptEvent.Turn(next(), 0, TurnPhase.COMPLETED, null, null)))
        assertFalse(reads(TranscriptEvent.Turn(next(), 0, TurnPhase.STARTED, null, null)))
        model.hidden()
    }

    @Test
    fun aBurstOfChangesCostsTwoReadsOfTheSubagentsNotOneEach() = runTest(dispatcher) {
        features.features += Features.RUNNERS_CLOUD_TRANSCRIPT_FEEDS
        val gate = CompletableDeferred<Unit>()
        transcripts.agentsHold = { gate.await() }
        val model = model()
        model.visible(); settle()
        assertEquals(1, agentReads())
        push(TranscriptEvent.Tasks(next(), 0, emptyList(), stoppable = true), TranscriptEvent.Tasks(next(), 0, emptyList(), stoppable = true))
        assertEquals(1, agentReads(), "a read is on its way")
        gate.complete(Unit); settle()
        assertEquals(2, agentReads())
        model.hidden()
    }

    // ---- Messages and files ---------------------------------------------------------------------------------------

    @Test
    fun pendingMessagesAreDiscardedRetriedAndMappedOneByOne() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        val gate = CompletableDeferred<Unit>()
        var receipts = 0
        repo.sendResult = { gate.await(); InputReceipt("in-${++receipts}", "queued", woke = false, reason = null) }
        model.onDraftChange("one"); model.send(); model.onDraftChange("two"); model.send(); settle()
        val (first, second) = model.uiState.value.pending
        assertEquals(listOf(PendingMessage.State.SENDING, PendingMessage.State.SENDING), model.uiState.value.pending.map { it.state })
        model.discard(first.clientMessageId); model.discard("nobody")
        assertEquals(2, model.uiState.value.pending.size, "a message on its way is not discarded")
        gate.complete(Unit); settle()
        assertEquals(listOf(PendingMessage.State.SENT, PendingMessage.State.SENT), model.uiState.value.pending.map { it.state })
        model.discard(first.clientMessageId)
        assertEquals(listOf(second.clientMessageId), model.uiState.value.pending.map { it.clientMessageId })

        repo.sendResult = { throw AppException.Unreachable() }
        model.onDraftChange("three"); model.send(); settle()
        val failed = model.uiState.value.pending.single { it.state == PendingMessage.State.FAILED }
        model.retry("nobody"); model.retry(second.clientMessageId); settle()
        assertEquals(3, repo.calls.count { it.startsWith("send") }, "only a failed message is sent again")
        repo.sendResult = { InputReceipt("in-9", "queued", woke = false, reason = null) }
        model.retry(failed.clientMessageId); settle()
        assertEquals(4, repo.calls.count { it.startsWith("send") })
        assertEquals(listOf(PendingMessage.State.SENT, PendingMessage.State.SENT), model.uiState.value.pending.map { it.state })
        model.hidden()
    }

    private fun filesModel(repository: FakeFileRepository): CloudSessionViewModel {
        features.features += Features.RUNNERS_CLOUD_FILES
        return model(files = SessionFilesUseCase(repository, features, fakeGuard(), SequenceIds()))
    }

    @Test
    fun filesAreAttachedRemovedAndSentWithTheMessage() = runTest(dispatcher) {
        val model = filesModel(FakeFileRepository())
        model.visible(); settle()
        assertNotNull(model.uiState.value.files.policy)
        model.attach(emptyList()); settle()
        assertTrue(model.uiState.value.files.files.isEmpty())
        model.attach(listOf(picked("a.png"), picked("b.png"))); settle()
        val ids = model.uiState.value.files.files.map { it.localId }
        assertEquals(2, ids.size)
        model.removeFile(ids[1]); settle()
        assertEquals(listOf(ids[0]), model.uiState.value.files.files.map { it.localId })
        model.onDraftChange("See"); model.send(); settle()
        assertTrue(model.uiState.value.files.files.isEmpty(), "the message took the files")
        val sent = repo.calls.single { it.startsWith("send") }
        assertTrue(sent.contains("files=f-"), sent)
        model.hidden()
    }

    @Test
    fun aFailedUploadIsRetriedAndTheComposerExplainsWhatItCannotDo() = runTest(dispatcher) {
        val repository = FakeFileRepository()
        repository.result = { _, _ -> throw AppException.Unreachable() }
        val model = filesModel(repository)
        model.visible(); settle()
        model.attach(listOf(picked("a.png"))); settle()
        val failed = model.uiState.value.files.files.single()
        assertEquals(PendingFile.State.FAILED, failed.state)
        repository.result = { file, key -> UploadedFile("f-$key", file.name, file.size, "image/png") }
        model.retryFile(failed.localId); settle()
        assertEquals(PendingFile.State.UPLOADED, model.uiState.value.files.files.single().state)
        model.pickFailed()
        assertEquals(FileProblem.Unreadable, model.uiState.value.files.notice)
        model.dismissFileNotice()
        assertNull(model.uiState.value.files.notice)
        model.explainFiles()
        assertNull(model.uiState.value.files.notice, "there is room: nothing to explain")
        model.hidden()

        val off = filesModel(FakeFileRepository(policy(available = false)))
        off.visible(); settle()
        off.explainFiles()
        assertEquals(FileProblem.StorageNotConfigured, off.uiState.value.files.notice)
        off.hidden()
    }

    @Test
    fun stoppingATaskWithNothingAskedSendsNothing() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        model.confirmStop(); settle()
        assertTrue(repo.calls.none { it.startsWith("stopTask") })
        assertTrue(model.uiState.value.stopping.isEmpty())
        model.hidden()
    }

    // ---- Permission cards -----------------------------------------------------------------------------------------

    @Test
    fun choicesFollowTheKindOfEachQuestion() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        push(ask())
        model.selectOption("r1", 9, 0); model.selectOther("r1", 9); model.onOtherText("r1", 9, "x")
        assertTrue(model.uiState.value.drafts.isEmpty(), "a question that is not asked changes nothing")
        // A multi-select question toggles its options and "Other".
        model.selectOption("r1", 1, 2); model.selectOption("r1", 1, 2)
        assertEquals(emptySet<Int>(), model.choice(1)?.options)
        model.selectOption("r1", 1, 0); model.selectOther("r1", 1)
        assertEquals(QuestionChoice(setOf(0), other = true), model.choice(1))
        model.selectOther("r1", 1)
        assertEquals(QuestionChoice(setOf(0), other = false), model.choice(1))
        model.onOtherText("r1", 1, "Huge")
        assertEquals(QuestionChoice(setOf(0), other = true, otherText = "Huge"), model.choice(1))
        // In a single-choice one, "Other" replaces the option and an option replaces "Other".
        model.selectOption("r1", 0, 1); model.selectOther("r1", 0)
        assertEquals(QuestionChoice(emptySet(), other = true), model.choice(0))
        model.selectOption("r1", 0, 0)
        assertEquals(QuestionChoice(setOf(0), other = false), model.choice(0))
        model.onOtherText("r1", 0, "Green")
        assertEquals(QuestionChoice(emptySet(), other = true, otherText = "Green"), model.choice(0))
        // A request that is not the one shown changes nothing.
        val drafts = model.uiState.value.drafts
        model.selectOption("nobody", 0, 0); model.selectOther("nobody", 0); model.onOtherText("nobody", 0, "x")
        assertEquals(drafts, model.uiState.value.drafts)
        model.hidden()
    }

    @Test
    fun otherIsPickedAndTypedBeforeAnyOptionWasChosen() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        push(ask())
        model.selectOther("r1", 1)
        assertEquals(QuestionChoice(emptySet(), other = true), model.choice(1))
        model.onOtherText("r1", 0, "Green")
        assertEquals(QuestionChoice(emptySet(), other = true, otherText = "Green"), model.choice(0))
        model.hidden()
    }

    @Test
    fun nextStopsAtTheLastQuestionAndAnswersAreSentOnlyOnceItIsAnswered() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        push(ask(questions = listOf(colour, sizes)))
        model.sendAnswers("nobody")
        model.selectOption("r1", 0, 0)
        model.sendAnswers("r1")
        model.nextQuestion("r1")
        model.sendAnswers("r1")
        assertTrue(repo.answers.isEmpty(), "the last question is not answered yet")
        model.selectOption("r1", 1, 1)
        val before = model.uiState.value.drafts
        model.nextQuestion("r1")
        assertEquals(before, model.uiState.value.drafts, "there is no question after the last one")
        model.sendAnswers("r1"); settle()
        assertEquals(1, repo.answers.size)
        model.sendAnswers("r1"); settle()
        assertEquals(1, repo.answers.size, "a sent answer is not sent again")
        model.hidden()
    }

    @Test
    fun aQuestionWhoseInputWasLeftOutCanOnlyBeDeclinedAndToolsAreNotAnsweredLikeQuestions() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        push(TranscriptEvent.PermissionRequest(next(), 0, "q1", "AskUserQuestion", true, listOf(colour), input = null, inputLeftOut = true))
        model.selectOption("q1", 0, 0); model.sendAnswers("q1"); model.allowTool("q1"); model.bypass("q1"); settle()
        assertTrue(repo.answers.isEmpty())
        model.deny("q1"); settle()
        assertEquals(PermissionAnswer("q1", allow = false), repo.answers.single().first)
        push(TranscriptEvent.InputChanged(next(), 0, "ans-q1", "consumed", null, requestId = "q1"), tool("t2", leftOut = true))
        model.bypass("t2"); settle()
        assertEquals(1, repo.answers.size, "a tool whose input was left out is not allowed by Bypass")
        push(TranscriptEvent.InputChanged(next(), 0, "ans-t2", "consumed", null, requestId = "t2"), tool("t1"))
        model.sendAnswers("t1"); settle()
        assertEquals(1, repo.answers.size, "a tool use has no answers to send")
        push(TranscriptEvent.InputChanged(next(), 0, "ans-t1", "consumed", null, requestId = "t1"), ask("r1", listOf(colour)))
        model.allowTool("r1"); model.bypass("r1"); settle()
        assertEquals(1, repo.answers.size, "a question is not allowed like a tool use")
        assertTrue(repo.calls.none { it.startsWith("bypass") })
        model.hidden()
    }

    @Test
    fun aToolIsAnsweredOnlyWhileItsCardIsEditable() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        push(tool("t1"))
        model.allowTool("nobody"); model.deny("nobody"); model.retryAnswer("nobody"); model.bypass("nobody")
        model.retryAnswer("t1")
        settle()
        assertTrue(repo.answers.isEmpty(), "nothing was sent that could be sent again")
        model.allowTool("t1"); settle()
        assertEquals(AnswerState.SENT, model.uiState.value.permission?.draft?.state)
        model.allowTool("t1"); model.deny("t1"); model.bypass("t1"); model.retryAnswer("t1"); settle()
        assertEquals(1, repo.answers.size)
        assertTrue(repo.calls.none { it.startsWith("bypass") })
        model.hidden()
    }

    @Test
    fun anAnswerThatIsEditedAfterAFailureOrDeniedGetsANewKey() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        push(ask(questions = listOf(colour)))
        model.selectOption("r1", 0, 0)
        repo.answerResult = { throw AppException.Unreachable() }
        model.sendAnswers("r1"); settle()
        assertEquals(AnswerState.FAILED, model.uiState.value.permission?.draft?.state)
        model.deny("r1"); settle()
        val keys = repo.answers.map { it.second }
        assertEquals(2, keys.size)
        assertNotEquals(keys[0], keys[1])
        model.hidden()
    }

    @Test
    fun aRefusalWithoutACodeIsSaidAndAnAnswerForAClosedRequestLeavesNoDraft() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        push(tool("t1"))
        repo.answerResult = { throw AppException.Server(403, null) }
        model.deny("t1"); settle()
        assertIs<SessionNotice.Text>(model.uiState.value.notice)

        val gate = CompletableDeferred<Unit>()
        repo.answerResult = { gate.await(); InputReceipt("ans-t1", "queued", woke = false, reason = null) }
        model.allowTool("t1"); settle()
        assertEquals(AnswerState.SENDING, model.uiState.value.permission?.draft?.state)
        push(TranscriptEvent.InputChanged(next(), 0, "ans-t1", "consumed", null, requestId = "t1"))
        assertNull(model.uiState.value.permission)
        assertTrue(model.uiState.value.drafts.isEmpty())
        gate.complete(Unit); settle()
        assertTrue(model.uiState.value.drafts.isEmpty())
        model.hidden()
    }

    @Test
    fun aRejectedAnswerWithoutAReasonIsSaidByItsStatus() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        push(ask(questions = listOf(colour)))
        model.selectOption("r1", 0, 0); model.sendAnswers("r1"); settle()
        push(TranscriptEvent.InputChanged(next(), 0, "ans-r1", "rejected", null, requestId = "r1"))
        assertEquals(SessionNotice.PermissionRejected("rejected"), model.uiState.value.notice)
        assertEquals(AnswerState.EDITING, model.uiState.value.permission?.draft?.state)
        model.hidden()
    }

    @Test
    fun aRequestWithAnEmptyIdCannotBeAnswered() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        push(tool(""))
        assertEquals("", model.uiState.value.permission?.requestId)
        model.deny(""); settle()
        assertTrue(repo.answers.isEmpty())
        model.hidden()
    }

    @Test
    fun bypassWaitsForTheApprovalAndAllowsNothingWhenTheCardIsGoneOrAnswered() = runTest(dispatcher) {
        val model = model()
        model.visible(); settle()
        push(tool("t1"))
        val first = CompletableDeferred<Unit>()
        repo.changeResult = { first.await(); it }
        model.bypass("t1"); settle()
        assertTrue(model.uiState.value.busy)
        model.bypass("t1"); settle()
        assertEquals(1, repo.calls.count { it.startsWith("bypass") }, "one approval is on its way at a time")
        push(TranscriptEvent.PermissionExpired(next(), 0, "t1"))
        assertNull(model.uiState.value.permission)
        first.complete(Unit); settle()
        assertTrue(repo.answers.isEmpty(), "the request closed meanwhile")
        assertFalse(model.uiState.value.busy)
        assertTrue(model.uiState.value.session!!.permissionBypass)

        val second = CompletableDeferred<Unit>()
        repo.changeResult = { second.await(); it }
        push(tool("t2"))
        model.bypass("t2"); settle()
        model.deny("t2"); settle()
        second.complete(Unit); settle()
        assertEquals(listOf(PermissionAnswer("t2", allow = false)), repo.answers.map { it.first }, "answered meanwhile: Bypass adds nothing")
        model.hidden()
    }
}
