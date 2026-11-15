// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../Fakes.kt (FakeSessionRepository); see NOTICE.
package run.ostrom.app

import run.ostrom.app.domain.usecases.files.SessionFilesUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.NewDeviceSession
import run.ostrom.app.domain.models.SessionKind
import run.ostrom.app.domain.models.CloudSessionPage
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.InputReceipt
import run.ostrom.app.domain.models.KnownAgent
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.NewCloudSession
import run.ostrom.app.domain.models.PermissionAnswer
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TranscriptPage
import run.ostrom.app.domain.repositories.CloudSessionRepository
import run.ostrom.app.domain.repositories.TranscriptRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.domain.usecases.sessions.AnswerPermissionUseCase
import run.ostrom.app.domain.usecases.sessions.CloudSessionAccess
import run.ostrom.app.domain.usecases.sessions.ControlCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.ControlConversationUseCase
import run.ostrom.app.domain.usecases.sessions.GetCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionOptionsUseCase
import run.ostrom.app.domain.usecases.sessions.SendMessageUseCase
import run.ostrom.app.domain.usecases.sessions.SetCloudSessionGitHubAccessUseCase
import run.ostrom.app.domain.usecases.transcript.FollowTranscriptUseCase
import run.ostrom.app.domain.usecases.transcript.ListAgentsUseCase
import run.ostrom.app.domain.usecases.transcript.LoadTranscriptUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.presentation.navigation.CloudSessionRoute
import run.ostrom.app.presentation.viewmodels.CloudConversationUseCases
import run.ostrom.app.presentation.viewmodels.CloudSessionViewModel

/**
 * The conversation calls of a [CloudSessionRepository] (`runners.cloud.conversation.v1` and after), recorded in [calls]
 * with results a test sets; a test fake of the other calls extends it.
 */
abstract class ConversationCalls : CloudSessionRepository {
    val calls = mutableListOf<String>()

    /** Every answer sent, with its idempotency key. */
    val answers = mutableListOf<Pair<PermissionAnswer, String>>()
    var sendResult: suspend (String) -> InputReceipt = { InputReceipt("in-${calls.size}", "queued", woke = false, reason = null) }
    var interruptResult: suspend () -> InputReceipt = { InputReceipt("int-${calls.size}", "queued", woke = false, reason = null) }
    var answerResult: suspend (PermissionAnswer) -> InputReceipt = { InputReceipt("ans-${it.requestId}", "queued", woke = false, reason = null) }
    var stopTaskResult: suspend (String) -> InputReceipt = { InputReceipt("stop-$it", "queued", woke = false, reason = null) }
    var changeResult: suspend (CloudSession) -> CloudSession = { it }

    /** Every session started on a device, with its idempotency key. */
    val deviceCreations = mutableListOf<Pair<NewDeviceSession, String>>()
    var createOnDeviceResult: suspend (NewDeviceSession) -> CloudSession = { current("remote-new").copy(kind = SessionKind.REMOTE) }

    override suspend fun createOnDevice(accountId: String, session: NewDeviceSession, idempotencyKey: String): CloudSession {
        calls += "createOnDevice $idempotencyKey"
        deviceCreations += session to idempotencyKey
        return createOnDeviceResult(session)
    }

    /** The session the model, effort and Bypass changes start from. */
    abstract fun current(sessionId: String): CloudSession

    /** Keeps a changed session, for the fakes that read it again. */
    open fun changed(session: CloudSession): CloudSession = session

    override suspend fun send(accountId: String, sessionId: String, text: String, clientMessageId: String, fileIds: List<String>): InputReceipt {
        calls += "send $sessionId $text $clientMessageId" + if (fileIds.isEmpty()) "" else " files=" + fileIds.joinToString(",")
        return sendResult(text)
    }

    override suspend fun interrupt(accountId: String, sessionId: String): InputReceipt {
        calls += "interrupt $sessionId"
        return interruptResult()
    }

    override suspend fun answer(accountId: String, sessionId: String, answer: PermissionAnswer, idempotencyKey: String): InputReceipt {
        calls += "answer $sessionId ${answer.requestId} $idempotencyKey"
        answers += answer to idempotencyKey
        return answerResult(answer)
    }

    override suspend fun stopTask(accountId: String, sessionId: String, taskId: String): InputReceipt {
        calls += "stopTask $sessionId $taskId"
        return stopTaskResult(taskId)
    }

    override suspend fun setPermissionBypass(accountId: String, sessionId: String, enabled: Boolean): CloudSession {
        calls += "bypass $sessionId $enabled"
        return changed(changeResult(current(sessionId).copy(permissionBypass = enabled)))
    }

    override suspend fun setModel(accountId: String, sessionId: String, model: String): CloudSession {
        calls += "setModel $sessionId $model"
        return changed(changeResult(current(sessionId).copy(model = model)))
    }

    override suspend fun setEffort(accountId: String, sessionId: String, effort: String): CloudSession {
        calls += "setEffort $sessionId $effort"
        return changed(changeResult(current(sessionId).copy(effort = effort)))
    }
}

/** One cloud session, [session], whose every call is recorded in [calls]. */
class FakeCloudSessionRepository(var session: CloudSession) : ConversationCalls() {
    /** What reading the session throws, when set. */
    var sessionFailure: Throwable? = null

    override fun current(sessionId: String) = session
    override fun changed(session: CloudSession): CloudSession { this.session = session; return session }
    override suspend fun sessions(accountId: String, before: String?, kinds: Set<SessionKind>) = CloudSessionPage(listOf(session), null)
    override suspend fun session(accountId: String, sessionId: String): CloudSession {
        calls += "get $sessionId"
        sessionFailure?.let { throw it }
        return session
    }
    override suspend fun create(accountId: String, session: NewCloudSession, idempotencyKey: String) = this.session
    override suspend fun pause(accountId: String, sessionId: String): CloudSession { calls += "pause $sessionId"; return changed(session.copy(status = CloudSessionStatus.PAUSING)) }
    override suspend fun resume(accountId: String, sessionId: String): CloudSession { calls += "resume $sessionId"; return changed(session.copy(status = CloudSessionStatus.RESUMING)) }
    override suspend fun stop(accountId: String, sessionId: String): CloudSession { calls += "stop $sessionId"; return session }
    override suspend fun setGitHubAccess(accountId: String, sessionId: String, access: GitHubAccess) = session.copy(githubAccess = access)
}

/** A transcript a test sets: [pages] by feed (null: the whole transcript), [live] what the stream sends. */
class FakeTranscriptRepository : TranscriptRepository {
    val asked = mutableListOf<String>()
    var pages: MutableMap<String?, TranscriptPage> = mutableMapOf()
    var olderPages: MutableMap<String?, TranscriptPage> = mutableMapOf()
    var agents: List<KnownAgent> = emptyList()
    var pageFailure: Throwable? = null
    val live = MutableSharedFlow<LiveUpdate<TranscriptEvent>>(replay = 16)

    override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage = feedPage(accountId, sessionId, before, null)

    override suspend fun feedPage(accountId: String, sessionId: String, before: Long?, feed: String?): TranscriptPage {
        asked += "page ${feed ?: "all"} ${before ?: "newest"}"
        pageFailure?.let { throw it }
        val page = if (before == null || before == Long.MAX_VALUE) pages[feed] else olderPages[feed]
        return page ?: TranscriptPage(emptyList(), null, 0)
    }

    override suspend fun agents(accountId: String, sessionId: String): List<KnownAgent> {
        asked += "agents"
        return agents
    }

    override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> {
        asked += "follow $after"
        return live
    }
}

/** The screen model of [sessions]' session, signed in as the member [memberId], over [transcripts] and [features]. */
fun conversationViewModel(
    sessions: FakeCloudSessionRepository,
    transcripts: TranscriptRepository,
    features: FakeFeatures,
    memberId: String? = "m1",
    files: SessionFilesUseCase? = null,
): CloudSessionViewModel {
    val accounts = accounts().also { it.save(account("a1", memberId = memberId)) }
    val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts, MemoryCredentialStore(), FixedClock()))
    val access = CloudSessionAccess(sessions, features, guard)
    return CloudSessionViewModel(
        CloudSessionRoute("a1", sessions.session.id), GetCloudSessionUseCase(access), ControlCloudSessionUseCase(access),
        LoadCloudSessionOptionsUseCase(access), SetCloudSessionGitHubAccessUseCase(access),
        CloudConversationUseCases(
            LoadTranscriptUseCase(transcripts, features, guard), FollowTranscriptUseCase(transcripts, guard), ListAgentsUseCase(transcripts, features, guard),
            SendMessageUseCase(sessions, features, guard, SequenceIds()), ControlConversationUseCase(sessions, guard, features),
            AnswerPermissionUseCase(sessions, features, guard, SequenceIds()), accounts, files,
        ),
    )
}
