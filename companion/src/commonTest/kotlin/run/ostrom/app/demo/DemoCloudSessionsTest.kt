// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.demo

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import run.ostrom.app.FixedClock
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudAgent
import run.ostrom.app.domain.models.CloudRecipe
import run.ostrom.app.domain.models.CloudRepository
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionRefusal
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.models.NewDeviceSession
import run.ostrom.app.domain.models.PermissionAnswer
import run.ostrom.app.domain.models.PermissionMode
import run.ostrom.app.domain.models.PickedFile
import run.ostrom.app.domain.models.SessionKind
import run.ostrom.app.domain.models.TransferDirection
import run.ostrom.app.domain.models.TransferRequest
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TurnPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The demo's cloud sessions, devices, transfers, files and transcripts: what a person does there changes what they see. */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoCloudSessionsTest {
    private val clock = FixedClock(30_000_000L)
    private val demo = DemoMode(clock).also { it.start() }
    private val sessions = DemoCloudSessionRepository(demo, clock)
    private val transcripts = DemoTranscriptRepository(demo)
    private val account = DemoContent.ACCOUNT_ID
    private val running = DemoCloudSessionIds.RUNNING
    private val talk = DemoCloudSessionIds.CONVERSATION

    private fun events(id: String) = demo.store().transcripts.value[id].orEmpty()

    @Test fun withoutKindsOnlyCloudSessionsAreListedAndWithKindsTheOnesAsked() = runTest {
        assertEquals(
            listOf(talk, running, DemoCloudSessionIds.PREPARING, DemoCloudSessionIds.RETRYABLE, DemoCloudSessionIds.BROKEN),
            sessions.sessions(account, null, emptySet()).sessions.map { it.id },
        )
        assertEquals(
            listOf(DemoCloudSessionIds.REMOTE, DemoCloudSessionIds.LOCAL),
            sessions.sessions(account, null, setOf(SessionKind.REMOTE, SessionKind.LOCAL)).sessions.map { it.id },
        )
        assertTrue(sessions.sessions(account, "c", emptySet()).sessions.isEmpty())
        assertEquals(running, sessions.session(account, running).id)
        assertFailsWith<AppException.NotFound> { sessions.session(account, "ghost") }
    }

    @Test fun aNewSessionIsNamedByItsNameThenItsFirstRecipeRepositoryThenItsRepoThenItsAgent() = runTest {
        suspend fun named(session: NewCloudSession) = sessions.create(account, session, "k").name
        assertEquals("Mine", named(NewCloudSession(CloudAgent.CLAUDE, name = "Mine")))
        val recipe = CloudRecipe(repositories = listOf(CloudRepository("https://github.com/acme/engine.git")))
        assertEquals("engine", named(NewCloudSession(CloudAgent.CODEX, repo = "https://github.com/acme/other", recipe = recipe)))
        assertEquals("other", named(NewCloudSession(CloudAgent.CODEX, repo = "https://github.com/acme/other")))
        assertEquals("gemini", named(NewCloudSession(CloudAgent.GEMINI)))
        assertEquals("opencode", named(NewCloudSession(CloudAgent.OPENCODE, recipe = CloudRecipe())))
    }

    @Test fun aNewSessionRunsAtOnceBelongsToAdaAndComesFirst() = runTest {
        val created = sessions.create(account, NewCloudSession(CloudAgent.CLAUDE, model = "opus", repo = "https://github.com/acme/engine", ref = "main"), "k")
        assertEquals("demo-cloud-new-8", created.id)
        assertEquals(CloudSessionStatus.RUNNING, created.status)
        assertEquals(DemoContent.MEMBER_ID, created.ownerId)
        assertEquals(created.id, sessions.sessions(account, null, emptySet()).sessions.first().id)
        assertEquals(clock.now, created.createdAt)
    }

    @Test fun aSessionOnTheLaptopStartsInTheChosenFolderAndAnUnpublishedFolderIsRefused() = runTest {
        val request = NewDeviceSession("Loom", DemoDevices.LAPTOP, "notes", "claude", "opus", "high", PermissionMode.BYPASS, null)
        val created = sessions.createOnDevice(account, request, "k")
        assertEquals("demo-remote-new-8", created.id)
        assertEquals(SessionKind.REMOTE, created.kind)
        assertEquals("notes", created.host?.directoryName)
        assertEquals(PermissionMode.BYPASS.wire, created.host?.permissionMode)
        assertEquals("Ada's MacBook", created.host?.deviceName)
        assertEquals("high", created.effort)
        assertEquals("folder_not_published", assertFailsWith<AppException.DeviceRefused> { sessions.createOnDevice(account, request.copy(folderId = "secret"), "k") }.code)
    }

    @Test fun pausingResumingAndStoppingChangeTheSessionAndEndedOnesRefuseChanges() = runTest {
        val paused = sessions.pause(account, running)
        assertEquals(CloudSessionStatus.PAUSED, paused.status)
        assertEquals(clock.now, paused.pausedAt)
        val resumed = sessions.resume(account, running)
        assertEquals(CloudSessionStatus.RUNNING, resumed.status)
        assertNull(resumed.pausedAt)
        val stopped = sessions.stop(account, running)
        assertEquals(CloudSessionStatus.TERMINATED, stopped.status)
        assertEquals(CloudSessionRefusal.STATE, assertFailsWith<AppException.CloudSessionRefused> { sessions.pause(account, running) }.reason)
        assertEquals(CloudSessionRefusal.STATE, assertFailsWith<AppException.CloudSessionRefused> { sessions.setModel(account, DemoCloudSessionIds.BROKEN, "m") }.reason)
        assertFailsWith<AppException.NotFound> { sessions.stop(account, "ghost") }
    }

    @Test fun theGitHubAccessCanBeTakenAwayOnlyFromAPausedSession() = runTest {
        assertEquals(CloudSessionRefusal.GITHUB_NOT_PAUSED, assertFailsWith<AppException.CloudSessionRefused> { sessions.setGitHubAccess(account, running, GitHubAccess.NONE) }.reason)
        assertEquals(CloudSessionRefusal.GITHUB_NOT_PAUSED, assertFailsWith<AppException.CloudSessionRefused> { sessions.setGitHubAccess(account, "ghost", GitHubAccess.NONE) }.reason)
        assertEquals(GitHubAccess.NONE, sessions.setGitHubAccess(account, DemoCloudSessionIds.RETRYABLE, GitHubAccess.NONE).githubAccess)
        assertEquals(GitHubAccess.OWNER, sessions.setGitHubAccess(account, running, GitHubAccess.OWNER).githubAccess)
    }

    @Test fun thePermissionBypassModelAndEffortAreChangedOnTheSession() = runTest {
        assertTrue(sessions.setPermissionBypass(account, talk, true).permissionBypass)
        assertEquals("sonnet", sessions.setModel(account, talk, "sonnet").model)
        assertEquals("low", sessions.setEffort(account, talk, "low").effort)
        assertEquals(listOf(true, "sonnet", "low"), sessions.session(account, talk).let { listOf(it.permissionBypass, it.model, it.effort) })
    }

    @Test fun aMessageRecordsTheExchangeWithTheScriptedReplyAndFinishesTheTurn() = runTest {
        val before = events(talk).size
        val receipt = sessions.send(account, talk, "Hello", "m1", emptyList())
        assertEquals("consumed", receipt.status)
        assertFalse(receipt.woke)
        val added = events(talk).drop(before)
        assertEquals(
            listOf("Hello", DEMO_REPLY),
            listOf(added.filterIsInstance<TranscriptEvent.UserMessage>().single().text, added.filterIsInstance<TranscriptEvent.AgentMessage>().single().text),
        )
        assertEquals(TurnPhase.COMPLETED, added.filterIsInstance<TranscriptEvent.Turn>().last().phase)
        assertEquals(added.map { it.seq }, ((before + 1L)..(before + added.size.toLong())).toList(), "sequence numbers continue the transcript")
    }

    @Test fun aMessageAboutAChoiceEndsWithAQuestionCardInsteadOfAFinishedTurn() = runTest {
        sessions.send(account, talk, "Please CHOOSE a note", "m2", emptyList())
        val question = events(talk).filterIsInstance<TranscriptEvent.PermissionRequest>().last()
        assertTrue(question.question)
        assertEquals("Which note should the results follow?", question.questions.single().text)
        assertEquals(listOf("Note G", "Note A"), question.questions.single().options.map { it.label })
        assertIs<TranscriptEvent.AgentMessage>(events(talk).dropLast(1).last())
    }

    @Test fun aMessageToAPausedSessionWakesItAndAnEndedOrUnknownSessionTakesNone() = runTest {
        val receipt = sessions.send(account, DemoCloudSessionIds.RETRYABLE, "wake", "m3", emptyList())
        assertTrue(receipt.woke)
        assertEquals(CloudSessionStatus.RUNNING, sessions.session(account, DemoCloudSessionIds.RETRYABLE).status)
        val ended = assertFailsWith<AppException.Server> { sessions.send(account, DemoCloudSessionIds.BROKEN, "x", "m4", emptyList()) }
        assertEquals(409 to "session_not_running", ended.statusCode to ended.code)
        assertFailsWith<AppException.NotFound> { sessions.send(account, "ghost", "x", "m5", emptyList()) }
    }

    @Test fun anInterruptEndsTheTurnAndAnAnswerIsConsumedAtOnce() = runTest {
        sessions.interrupt(account, talk)
        assertEquals(TurnPhase.INTERRUPTED, (events(talk).last() as TranscriptEvent.Turn).phase)
        assertEquals("demo-answer-perm-push", sessions.answer(account, talk, PermissionAnswer("perm-push", allow = true), "k").inputId)
        assertEquals("Done.", events(talk).filterIsInstance<TranscriptEvent.AgentMessage>().last().text)
        assertEquals("perm-push", events(talk).filterIsInstance<TranscriptEvent.InputChanged>().last().requestId)
        sessions.answer(account, talk, PermissionAnswer("perm-push", allow = false), "k2")
        assertEquals("Understood, I won't.", events(talk).filterIsInstance<TranscriptEvent.AgentMessage>().last().text)
    }

    @Test fun stoppingATaskLeavesItOutOfTheNextReportAndASessionWithoutTasksReportsNone() = runTest {
        assertEquals("demo-stop-task-tables", sessions.stopTask(account, talk, "task-tables").inputId)
        val report = events(talk).filterIsInstance<TranscriptEvent.Tasks>().last()
        assertTrue(report.tasks.isEmpty())
        assertTrue(report.stoppable)
        sessions.stopTask(account, running, "task-x")
        assertTrue(events(running).filterIsInstance<TranscriptEvent.Tasks>().single().tasks.isEmpty())
        assertEquals(1L, events(running).single().seq, "a transcript starts at one")
    }

    @Test fun theLaptopIsOnlineWithTwoFoldersAndTwoConversationsToResumeInTheEngineFolder() = runTest {
        val devices = DemoDeviceRepository(demo)
        assertEquals(listOf(DemoDevices.LAPTOP), devices.onlineDevices(account).map { it.id })
        val folders = devices.folders(account, DemoDevices.LAPTOP)
        assertTrue(folders.online && folders.resumes)
        assertEquals(listOf("engine", "notes"), folders.folders.map { it.id })
        assertEquals("device_not_found", assertFailsWith<AppException.DeviceRefused> { devices.folders(account, "other") }.code)
        assertEquals(2, devices.resumable(account, DemoDevices.LAPTOP, "engine", 10, null).sessions.size)
        assertTrue(devices.resumable(account, DemoDevices.LAPTOP, "notes", 10, null).sessions.isEmpty())
        assertTrue(devices.resumable(account, DemoDevices.LAPTOP, "engine", 10, "c").sessions.isEmpty())
    }

    @Test fun theDemoHasNoTransfersAndNoFiles() = runTest {
        val transfers = DemoTransferRepository
        assertEquals(Features.RUNNERS_CLOUD_TRANSFER, assertFailsWith<AppException.Unsupported> { transfers.transfer(account, running, TransferRequest("m"), "k") }.feature)
        assertTrue(transfers.transfers(account, TransferDirection.INCOMING, null).transfers.isEmpty())
        assertFailsWith<AppException.NotFound> { transfers.accept(account, "t", null) }
        assertFailsWith<AppException.NotFound> { transfers.decline(account, "t") }
        assertFailsWith<AppException.NotFound> { transfers.cancel(account, "t") }
        assertTrue(transfers.recipients(account).isEmpty())
        val files = DemoSessionFileRepository(object : run.ostrom.app.domain.repositories.SessionFileRepository {
            override suspend fun policy(accountId: String) = error("not used")
            override suspend fun upload(accountId: String, sessionId: String, file: PickedFile, idempotencyKey: String, onProgress: (Long, Long) -> Unit) = error("not used")
            override fun typeFor(name: String): String? = "type/$name"
        })
        assertFalse(files.policy(account).available)
        assertEquals("demo", files.policy(account).reason)
        assertEquals("type/a.png", files.typeFor("a.png"))
        assertEquals(Features.RUNNERS_CLOUD_FILES, assertFailsWith<AppException.Unsupported> { files.upload(account, running, PickedFile("a", 1) { Buffer() }, "k") { _, _ -> } }.feature)
    }

    @Test fun theTranscriptIsShownWholeOrByAgentAndBlankAfterTheFirstPage() = runTest {
        val whole = transcripts.page(account, talk, null)
        assertEquals(13, whole.events.size)
        assertEquals(13L, whole.lastSeq)
        assertTrue(transcripts.page(account, talk, 5).events.isEmpty())
        val main = transcripts.feedPage(account, talk, null, "main").events
        assertTrue(main.none { it is TranscriptEvent.ToolCall && it.parentToolUseId != null })
        assertTrue(main.any { it is TranscriptEvent.AgentMessage && it.parentToolUseId == null })
        val sub = transcripts.feedPage(account, talk, null, "t-explore").events
        assertEquals(listOf(6L, 7L, 8L), sub.map { it.seq })
        assertEquals(0L, transcripts.page(account, "unknown", null).lastSeq)
        assertTrue(transcripts.feedPage(account, talk, 3, "main").events.isEmpty())
    }

    @Test fun theSubagentIsRunningUntilItsResultAndThereIsNoneInAnotherSession() = runTest {
        val agent = transcripts.agents(account, talk).single()
        assertEquals("Explore", agent.type)
        assertEquals(run.ostrom.app.domain.models.KnownAgentState.COMPLETED, agent.state)
        assertEquals(1, agent.steps)
        demo.store().transcripts.value = mapOf(talk to events(talk).filterNot { it is TranscriptEvent.ToolResult && it.id == "t-explore" })
        val running = transcripts.agents(account, talk).single()
        assertEquals(run.ostrom.app.domain.models.KnownAgentState.RUNNING, running.state)
        assertNull(running.endedAt)
        assertTrue(transcripts.agents(account, "other").isEmpty())
    }

    @Test fun followingTheTranscriptSendsTheMissedEventsThenTheNewOnesAsTheyArrive() = runTest {
        val seen = mutableListOf<LiveUpdate<TranscriptEvent>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { transcripts.follow(account, talk, 12).take(4).collect { seen += it } }
        assertEquals(listOf(LiveUpdate.Opened(true), LiveUpdate.Item(events(talk).last())), seen)
        sessions.send(account, talk, "again", "m", emptyList())
        job.join()
        assertEquals(listOf(13L, 14L, 15L), seen.filterIsInstance<LiveUpdate.Item<TranscriptEvent>>().map { it.value.seq })
    }

    @Test fun followingAnEndedSessionSendsItsEventsThenEndsOnce() = runTest {
        sessions.stop(account, talk)
        val updates = transcripts.follow(account, talk, 11).toList()
        assertEquals(LiveUpdate.Opened(true), updates.first())
        assertEquals(listOf(12L, 13L), updates.filterIsInstance<LiveUpdate.Item<TranscriptEvent>>().map { it.value.seq })
        assertEquals(LiveUpdate.Ended, updates.last())
        assertEquals(1, updates.count { it == LiveUpdate.Ended })
    }

    @Test fun followingASessionThatHasNoTranscriptWaitsWithoutFailing() = runTest {
        val updates = mutableListOf<LiveUpdate<TranscriptEvent>>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { transcripts.follow(account, "unknown", 0).collect { updates += it } }
        assertEquals(listOf<LiveUpdate<TranscriptEvent>>(LiveUpdate.Opened(true)), updates)
        assertTrue(job.isActive, "nothing ends a session that does not exist: the stream waits")
        job.cancel()
    }

    @Test fun aMessageToASessionWithoutATranscriptStartsOneAtItsFirstEvent() = runTest {
        sessions.send(account, running, "hello", "m1", emptyList())
        val events = transcripts.page(account, running, null).events
        assertEquals(1L, events.first().seq)
        assertTrue(events.size >= 2, "the message and the answer to it: $events")
    }

    @Test fun cloudSessionsOfTheDemoKeepTheirRecipesAndTheRunningOnesAreReady() = runTest {
        val all = sessions.sessions(account, null, SessionKind.entries.toSet()).sessions
        assertEquals(7, all.size)
        assertTrue(all.filter { it.status == CloudSessionStatus.RUNNING }.all { it.readyAt != null })
        assertEquals(2, sessions.session(account, running).recipe?.repositories?.size)
        assertIs<CloudSession>(sessions.session(account, DemoCloudSessionIds.LOCAL))
    }
}
