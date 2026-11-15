// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/SessionViewModel.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import run.ostrom.app.domain.models.AgentQuestion
import run.ostrom.app.domain.models.AnswerOutcome
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.GitHubAccess
import run.ostrom.app.domain.models.KnownAgent
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.PendingMessage
import run.ostrom.app.domain.models.PermissionAnswer
import run.ostrom.app.domain.models.QuestionChoice
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TurnPhase
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.find
import run.ostrom.app.domain.usecases.sessions.AnswerPermissionUseCase
import run.ostrom.app.domain.usecases.sessions.ControlCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.ControlConversationUseCase
import run.ostrom.app.domain.usecases.sessions.ConversationOptions
import run.ostrom.app.domain.usecases.sessions.GetCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionOptionsUseCase
import run.ostrom.app.domain.usecases.sessions.PermissionRules
import run.ostrom.app.domain.usecases.sessions.SendMessageUseCase
import run.ostrom.app.domain.usecases.sessions.SetCloudSessionGitHubAccessUseCase
import run.ostrom.app.domain.usecases.sessions.openPermissionRequests
import run.ostrom.app.domain.usecases.sessions.reconciled
import run.ostrom.app.domain.usecases.transcript.FollowTranscriptUseCase
import run.ostrom.app.domain.usecases.transcript.ListAgentsUseCase
import run.ostrom.app.domain.usecases.transcript.LoadTranscriptUseCase
import run.ostrom.app.domain.usecases.transcript.Transcript
import run.ostrom.app.presentation.navigation.CloudSessionRoute
import run.ostrom.app.presentation.transcript.AGENT_TOOLS

/** An action on a cloud session. */
enum class CloudAction { PAUSE, RESUME, STOP }

enum class LiveState { CONNECTING, LIVE, RECONNECTING, ENDED, STOPPED }

/** A short message under the header of a conversation; the fixed ones are worded by the screen, from its string resources. */
sealed interface SessionNotice {
    data class Text(val text: Failure) : SessionNotice
    data object NothingRunning : SessionNotice
    /** A `409`: the request was answered by someone else, expired or ended; its card is gone. */
    data class PermissionClosed(val code: String?) : SessionNotice
    /** A `400`: the server refused the answer; the draft stays. */
    data class PermissionInvalid(val code: String?) : SessionNotice
    /** A `403 github_owner_only`: only the owner instructs an agent that uses their GitHub access. */
    data object PermissionDenied : SessionNotice
    /** An accepted answer the agent did not take (an `input` event rejected it): the request is still open. */
    data class PermissionRejected(val reason: String?) : SessionNotice
}

/** Where an answer to a permission request stands. */
enum class AnswerState { EDITING, SENDING, FAILED, SENT }

/**
 * The member's answer to one open request: the question shown ([step]), what was picked for each question (by its
 * index), and the answer on its way. [sending] and [key] go together: a retry sends exactly that answer under that key,
 * and any edit drops both, so a changed answer never reuses a key.
 */
data class PermissionDraft(
    val step: Int = 0,
    val choices: Map<Int, QuestionChoice> = emptyMap(),
    val state: AnswerState = AnswerState.EDITING,
    val sending: PermissionAnswer? = null,
    val key: String? = null,
    val inputId: String? = null,
    val reason: String? = null,
)

/** The request shown, oldest open first, with its draft; [waiting] counts the other open ones. */
data class PermissionCard(val request: TranscriptEvent.PermissionRequest, val draft: PermissionDraft, val waiting: Int) {
    val requestId: String get() = request.requestId.orEmpty()
    /** A question whose input was left out (or unreadable) can only be declined, like a tool use whose input was. */
    val answerable: Boolean get() = !request.inputLeftOut && (!request.question || request.questions.isNotEmpty())
    val step: Int get() = draft.step.coerceIn(0, (request.questions.size - 1).coerceAtLeast(0))
    val current: AgentQuestion? get() = request.questions.getOrNull(step)
    val last: Boolean get() = step >= request.questions.size - 1
    val currentAnswered: Boolean get() = current?.let { PermissionRules.answer(it, draft.choices[it.index]) != null } == true
    val editable: Boolean get() = draft.state == AnswerState.EDITING || draft.state == AnswerState.FAILED
}

data class CloudSessionUiState(
    val session: CloudSession? = null,
    val loading: Boolean = true,
    val gone: Boolean = false,
    val error: Failure? = null,
    /** The action under way; null when none. */
    val acting: CloudAction? = null,
    /** The failure of the last action, apart from the page's. */
    val actionError: Failure? = null,
    /** The server lets the person choose the session's GitHub access (`runners.cloud.github.v1`). */
    val githubAccessOffered: Boolean = false,
    /** The GitHub access being set; null when none. */
    val settingAccess: GitHubAccess? = null,
    // ---- A conversation session (`runners.cloud.conversation.v1`), TeamCode's SessionUiState -----------------------
    /** What the server announces of conversations: each part of the screen shows only with its key. */
    val options: ConversationOptions = ConversationOptions(),
    val transcript: Transcript = Transcript(),
    /** The transcript's first page arrived. */
    val loaded: Boolean = false,
    val loadingOlder: Boolean = false,
    val live: LiveState = LiveState.STOPPED,
    /** A failure that ended the transcript's stream or one of its pages. */
    val transcriptError: Failure? = null,
    /** Messages sent from here that the transcript does not show yet, or that failed. */
    val pending: List<PendingMessage> = emptyList(),
    val draft: String = "",
    /** The member owns the session: Bypass, the model and the effort are theirs. */
    val canControl: Boolean = false,
    /** The member signed in on this account: their own messages read as theirs. */
    val memberId: String? = null,
    /** An interrupt, Bypass, a model or an effort on its way. */
    val busy: Boolean = false,
    /** A short result of an action (nothing to interrupt) or its failure. */
    val notice: SessionNotice? = null,
    /** Answers being written or sent, by request id. */
    val drafts: Map<String, PermissionDraft> = emptyMap(),
    /** Requests the server said were closed (`409`) before the transcript did: their card stays hidden. */
    val closedRequests: Set<String> = emptySet(),
    /** The subagent whose thread shows (its `Agent` call id), null for the main agent's. */
    val thread: String? = null,
    /** The subagents panel opened on demand, where it is a screen of its own or slides over the conversation. */
    val panelOpen: Boolean = false,
    /** The subagents panel hidden by the member, where it sits beside the conversation. */
    val panelHidden: Boolean = false,
    /** The background task whose stop waits for the member's confirmation. */
    val confirmStop: String? = null,
    /** Tasks asked to stop that the runner still reports. */
    val stopping: Set<String> = emptySet(),
    /** The session's subagents as the server tracks them (`runners.cloud.transcript.feeds.v1`). */
    val agents: List<KnownAgent> = emptyList(),
    /** The subagent feeds whose page is on its way. */
    val loadingFeeds: Set<String> = emptySet(),
) {
    /** The choice shows on a session whose access the server says, while it is not over. */
    val canChooseAccess: Boolean get() = githubAccessOffered && session?.let { it.githubAccess != null && !it.status.ended } == true

    /** The session is talked to here: a conversation session on a server that serves its transcript. */
    val conversation: Boolean get() = options.conversation && session?.conversation == true

    /** The server pages each agent's events apart (`runners.cloud.transcript.feeds.v1`). */
    val feeds: Boolean get() = options.feeds

    /** The cursor of the next older page of [thread]'s events (null: the main agent's); null when everything was loaded. */
    fun olderOf(thread: String?): Long? = if (thread == null || !feeds) transcript.nextBefore else transcript.feeds[thread]

    /** An older page of [thread] is on its way. */
    fun loadingOlderOf(thread: String?): Boolean = if (thread == null || !feeds) loadingOlder else thread in loadingFeeds

    /** [thread]'s newest page was read (always, for the main agent's, once the first page is there, and without feeds). */
    fun threadLoaded(thread: String?): Boolean = if (thread == null || !feeds) loaded else thread in transcript.feeds

    /** The session's agent runs: only then has it background tasks and takes answers. */
    val agentRuns: Boolean get() = session?.status == CloudSessionStatus.RUNNING

    /** Send is offered: some text. */
    val canSend: Boolean get() = draft.isNotBlank()

    /** The requests still waiting, oldest first; none while the session takes no answer (paused, pausing, ended). */
    val openRequests: List<TranscriptEvent.PermissionRequest> by lazy {
        if (!options.permissions || session != null && !agentRuns) emptyList()
        else transcript.events.openPermissionRequests().filter { it.requestId !in closedRequests }
    }

    /** The permission card offers "Bypass": the server supports it, the member owns the session and has not turned it on yet. */
    val bypassOffered: Boolean get() = options.bypass && canControl && session?.permissionBypass == false

    /** The oldest open request, answered first: the agent asked them in this order and their deadlines pass in it. */
    val permission: PermissionCard? get() = openRequests.firstOrNull()?.let { PermissionCard(it, drafts[it.requestId] ?: PermissionDraft(), openRequests.size - 1) }

    /**
     * A turn is running: the session runs and its last loaded `turn` event is `started`. Ostrom's sessions have no
     * ready/active split (TeamCode's): the turn events alone say whether the agent works.
     */
    val running: Boolean get() {
        val lastTurn = transcript.events.lastOrNull { it is TranscriptEvent.Turn } as TranscriptEvent.Turn?
        return (session == null || agentRuns) && lastTurn?.phase == TurnPhase.STARTED
    }
}

/** What a conversation session's screen uses beyond the record's: absent, the record shows as for a terminal session. */
class CloudConversationUseCases(
    val loadTranscript: LoadTranscriptUseCase,
    val followTranscript: FollowTranscriptUseCase,
    val listAgents: ListAgentsUseCase,
    val sendMessage: SendMessageUseCase,
    val control: ControlConversationUseCase,
    val answerPermission: AnswerPermissionUseCase,
    val accounts: AccountRepository,
)

/**
 * One cloud session's record with Pause, Resume and Stop (and the choice of its GitHub access, where the server offers
 * it): read on open, on return to the screen and on [load]; each action takes the server's answer, then reads the
 * session again. A conversation session (`mode: conversation`) adds TeamCode's conversation while the screen is visible
 * ([visible], [hidden]): the newest page of its transcript, older pages on demand, the live stream after the newest event,
 * the composer, Interrupt, the permission cards, Bypass, the model and effort, and the subagents and background tasks.
 * Events are merged by sequence, so nothing shows twice; the header is read again every 10 s, since the transcript does
 * not carry pauses, resumes or a failed pod.
 */
class CloudSessionViewModel(
    private val route: CloudSessionRoute,
    private val getSession: GetCloudSessionUseCase,
    private val control: ControlCloudSessionUseCase,
    private val options: LoadCloudSessionOptionsUseCase,
    private val setGitHubAccess: SetCloudSessionGitHubAccessUseCase,
    /** Null where the app does not talk to conversation sessions: their record shows as a terminal session's. */
    private val conversation: CloudConversationUseCases? = null,
) : ViewModel() {
    private val state = MutableStateFlow(CloudSessionUiState())
    val uiState: StateFlow<CloudSessionUiState> = state.asStateFlow()
    private var loading: Job? = null
    private var started = false

    /** The screen is visible: the record is read, and a conversation session is followed. */
    fun visible() {
        started = true
        load()
    }

    /** The screen left: the stream, the header's reads and the subagents' reads stop. */
    fun hidden() {
        started = false
        follow?.cancel(); follow = null
        agentsJob?.cancel(); agentsJob = null
        header?.cancel(); header = null
        state.update { if (it.live == LiveState.ENDED) it else it.copy(live = LiveState.STOPPED) }
    }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        loading?.cancel()
        loading = viewModelScope.launch {
            try {
                val asked = actions
                val session = getSession(route.accountId, route.sessionId)
                // A secondary probe: its failure leaves the choice hidden, never the record unread.
                val offered = try {
                    options(route.accountId).githubAccess
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    false
                }
                val talks = session.conversation && conversation != null
                val announced = if (talks) conversation.control.options(route.accountId) else ConversationOptions()
                state.update {
                    // A read that started before an action's answer is older than it: the action's session stays.
                    val shown = if (asked == actions || it.session == null) session else it.session
                    it.copy(
                        session = shown, loading = false, gone = false, githubAccessOffered = offered, options = announced, canControl = owns(shown),
                        memberId = conversation?.accounts?.find(route.accountId)?.memberId,
                    )
                }
                if (talks && announced.conversation && started) startConversation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: AppException.NotFound) {
                state.update { it.copy(loading = false, gone = true, error = error.userMessage()) }
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }

    fun pause() = act(CloudAction.PAUSE)
    fun resume() = act(CloudAction.RESUME)

    /** Stops the session for good (the screen confirms first). */
    fun stop() = act(CloudAction.STOP)

    /** Chooses whose GitHub access the session uses; a refusal is worded by its key, then the session is read again. */
    fun chooseGitHubAccess(choice: GitHubAccess) {
        val session = state.value.session ?: return
        if (!state.value.canChooseAccess || state.value.acting != null || state.value.settingAccess != null || session.githubAccess == choice) return
        state.update { it.copy(settingAccess = choice, actionError = null) }
        viewModelScope.launch {
            try {
                val changed = setGitHubAccess(route.accountId, session, choice)
                actions++
                state.update { it.copy(settingAccess = null, session = changed) }
            } catch (error: CancellationException) {
                state.update { it.copy(settingAccess = null) }
                throw error
            } catch (error: Exception) {
                state.update { it.copy(settingAccess = null, actionError = error.userMessage()) }
            }
            load()
        }
    }

    private fun act(action: CloudAction) {
        val session = state.value.session ?: return
        if (state.value.acting != null || state.value.settingAccess != null) return
        state.update { it.copy(acting = action, actionError = null) }
        viewModelScope.launch {
            try {
                val changed = when (action) {
                    CloudAction.PAUSE -> control.pause(route.accountId, session)
                    CloudAction.RESUME -> control.resume(route.accountId, session)
                    CloudAction.STOP -> control.stop(route.accountId, session)
                }
                actions++
                state.update { it.copy(acting = null, session = changed) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(acting = null, actionError = error.userMessage()) }
            }
            // Either way, read it again: what the server did meanwhile shows.
            load()
        }
    }

    // ---- The conversation ---------------------------------------------------------------------------------------------

    private val transcriptLock = Mutex()
    private var follow: Job? = null
    private var header: Job? = null

    /** Bumped by each action's answer: a header read that started before it is older and is dropped. */
    private var actions = 0

    private fun owns(session: CloudSession?): Boolean {
        val uses = conversation ?: return false
        return session != null && ControlConversationUseCase.canControl(uses.accounts.find(route.accountId)?.memberId, session)
    }

    private fun startConversation() {
        val uses = conversation ?: return
        // The transcript does not carry pauses, resumes or a failed pod: the header is read again while visible.
        if (header?.isActive != true) header = viewModelScope.launch {
            while (true) {
                delay(HEADER_MILLIS)
                // An ended session no longer changes: nothing to read.
                val current = state.value
                if (current.gone || current.session?.status?.ended == true) continue
                refreshSessionQuietly()
            }
        }
        // An ended session sent everything: nothing more to follow.
        if (follow?.isActive == true || state.value.gone || state.value.live == LiveState.ENDED) return
        state.update { it.copy(live = LiveState.CONNECTING, transcriptError = null) }
        follow = viewModelScope.launch {
            try {
                transcriptLock.withLock {
                    if (!state.value.loaded) {
                        // With feeds the conversation opens on the main agent's own events: a subagent's burst of steps
                        // never pushes its messages out of the page. Each subagent has its own pages, read when opened.
                        val feeds = state.value.feeds
                        val page = uses.loadTranscript(route.accountId, route.sessionId, feed = if (feeds) MAIN_FEED else null)
                        state.update { it.withTranscript(it.transcript.withLatest(page)).copy(loaded = true) }
                    }
                }
                refreshAgents()
                var reconnected = false
                uses.followTranscript(route.accountId, route.sessionId, state.value.transcript.newestSeq).collect { update ->
                    when (update) {
                        is LiveUpdate.Opened -> {
                            state.update { it.copy(live = LiveState.LIVE) }
                            // Back after a cut: the session may have been paused or resumed meanwhile.
                            if (reconnected) { reconnected = false; viewModelScope.launch { refreshSessionQuietly() }; refreshAgents() }
                        }
                        is LiveUpdate.Item -> {
                            state.update { it.withTranscript(it.transcript.withLive(update.value)).withTasks(update.value).copy(live = LiveState.LIVE) }
                            if (changesAgents(update.value)) refreshAgents()
                        }
                        is LiveUpdate.Reconnecting -> { reconnected = true; state.update { it.copy(live = LiveState.RECONNECTING) } }
                        LiveUpdate.Gap -> Unit
                        LiveUpdate.Ended -> {
                            state.update { it.copy(live = LiveState.ENDED) }
                            // The final state stays whatever the header read does.
                            viewModelScope.launch { refreshSessionQuietly() }
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Withdrawn access may come back (or was an expired token, which the guard signed out): retry stays offered.
                state.update { it.copy(live = LiveState.STOPPED, transcriptError = error.userMessage(), gone = it.gone || error is AppException.NotFound) }
            }
        }
    }

    /** After the stream stopped, follow again; while it runs, the error came from an older page: ask for it again. */
    fun retryTranscript() {
        if (!started) return
        state.update { it.copy(transcriptError = null) }
        if (follow?.isActive == true) loadOlder() else startConversation()
    }

    /** Older events of the thread on screen: its own feed's page, or the main agent's. */
    fun loadOlder() {
        val uses = conversation ?: return
        val thread = state.value.thread
        if (thread != null && state.value.feeds) { loadFeed(thread); return }
        val before = state.value.transcript.nextBefore ?: return
        if (state.value.loadingOlder) return
        state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                transcriptLock.withLock {
                    // Another page may have arrived meanwhile.
                    if (state.value.transcript.nextBefore != before) return@withLock
                    val page = uses.loadTranscript(route.accountId, route.sessionId, before, if (state.value.feeds) MAIN_FEED else null)
                    state.update { it.copy(transcript = it.transcript.withOlder(page)) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(transcriptError = error.userMessage()) }
            } finally {
                state.update { it.copy(loadingOlder = false) }
            }
        }
    }

    /**
     * The newest page of the subagent [feed] when it was never read, an older one when it was: a feed has its own cursor,
     * so going up in a subagent's thread never loads the main agent's history, nor the other way round.
     */
    private fun loadFeed(feed: String) {
        val uses = conversation ?: return
        val current = state.value
        if (!current.feeds || feed in current.loadingFeeds) return
        val read = feed in current.transcript.feeds
        val before = if (read) current.transcript.feeds[feed] ?: return else null
        state.update { it.copy(loadingFeeds = it.loadingFeeds + feed) }
        viewModelScope.launch {
            try {
                transcriptLock.withLock {
                    // Another page may have arrived meanwhile.
                    val now = state.value.transcript.feeds
                    if ((feed in now) != read || now[feed] != before && read) return@withLock
                    val page = uses.loadTranscript(route.accountId, route.sessionId, before, feed)
                    state.update { it.withTranscript(it.transcript.withFeed(feed, page)) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(transcriptError = error.userMessage()) }
            } finally {
                state.update { it.copy(loadingFeeds = it.loadingFeeds - feed) }
            }
        }
    }

    // ---- The server's list of subagents -------------------------------------------------------------------------------

    private var agentsJob: Job? = null
    private var agentsAgain = false

    /** An event after which the server's list of subagents may have changed: a subagent started, answered, or the tasks moved. */
    private fun changesAgents(event: TranscriptEvent): Boolean = when (event) {
        is TranscriptEvent.ToolCall -> event.name in AGENT_TOOLS || event.parentToolUseId != null && state.value.agents.any { it.id == event.parentToolUseId }
        is TranscriptEvent.ToolResult -> state.value.agents.any { it.id == event.id } ||
            event.parentToolUseId == null && state.value.transcript.events.any { it is TranscriptEvent.ToolCall && it.id == event.id && it.name in AGENT_TOOLS }
        is TranscriptEvent.Tasks -> true
        is TranscriptEvent.Turn -> event.phase.finished
        else -> false
    }

    /**
     * Reads the server's list of subagents: their start, state and step count without their events loaded. A request while
     * one runs asks for one more read after it, a second later at the earliest: a burst of events costs two reads, not one each.
     */
    private fun refreshAgents() {
        val uses = conversation ?: return
        if (!state.value.feeds) return
        if (agentsJob?.isActive == true) { agentsAgain = true; return }
        agentsJob = viewModelScope.launch {
            do {
                agentsAgain = false
                try {
                    val agents = uses.listAgents(route.accountId, route.sessionId)
                    state.update { it.copy(agents = agents) }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // The panel keeps what the loaded events show; the next change reads again.
                }
                if (agentsAgain) delay(AGENTS_MILLIS)
            } while (agentsAgain)
        }
    }

    fun onDraftChange(text: String) = state.update { it.copy(draft = text) }

    /** Shows the message at once, then sends it; the draft is cleared once it is queued for sending. */
    fun send() {
        val uses = conversation ?: return
        val message = try {
            uses.sendMessage.draft(state.value.draft.trim())
        } catch (error: AppException) {
            state.update { it.copy(notice = SessionNotice.Text(error.userMessage())) }
            return
        }
        // A new message replaces what the last action said (a refused draft, nothing to interrupt).
        state.update { it.copy(draft = "", pending = it.pending + message, notice = null) }
        deliver(message)
    }

    /** Sends a failed message again with the same id: the server never queues it twice. */
    fun retry(clientMessageId: String) {
        val message = state.value.pending.firstOrNull { it.clientMessageId == clientMessageId && it.state == PendingMessage.State.FAILED } ?: return
        val sending = message.copy(state = PendingMessage.State.SENDING, reason = null)
        state.update { current -> current.copy(pending = current.pending.map { if (it.clientMessageId == clientMessageId) sending else it }) }
        deliver(sending)
    }

    fun discard(clientMessageId: String) = state.update { current ->
        current.copy(pending = current.pending.filterNot { it.clientMessageId == clientMessageId && it.state != PendingMessage.State.SENDING })
    }

    private fun deliver(message: PendingMessage) {
        val uses = conversation ?: return
        viewModelScope.launch {
            val result = uses.sendMessage(route.accountId, route.sessionId, message)
            state.update { current ->
                current.copy(pending = current.pending.map { if (it.clientMessageId == message.clientMessageId) result else it }.reconciled(current.transcript.events))
            }
            // A paused session woke for it (the pending bubble says so): the header changes state.
            if (result.woke) refreshSessionQuietly()
        }
    }

    /**
     * Stops the current turn. Ostrom's interrupt takes no idempotency key (TeamCode keeps one per tap): one interrupt is
     * in flight at a time instead, and the button is disabled meanwhile.
     */
    fun interrupt() = busy { uses ->
        if (!uses.control.interrupt(route.accountId, route.sessionId)) state.update { it.copy(notice = SessionNotice.NothingRunning) }
    }

    // ---- Subagents and background tasks -------------------------------------------------------------------------------

    /** Shows the subagent [id]'s thread in place of the main agent's (or beside it, on an extra-large window). */
    fun openThread(id: String) { state.update { it.copy(thread = id) }; if (id !in state.value.transcript.feeds) loadFeed(id) }
    fun backToMain() = state.update { it.copy(thread = null) }
    fun togglePanel() = state.update { it.copy(panelOpen = !it.panelOpen) }
    fun closePanel() = state.update { it.copy(panelOpen = false) }
    fun togglePanelHidden() = state.update { it.copy(panelHidden = !it.panelHidden) }

    /** A stop is asked first: it ends work the agent may still need. */
    fun askStop(taskId: String) = state.update { it.copy(confirmStop = taskId) }
    fun cancelStop() = state.update { it.copy(confirmStop = null) }

    fun confirmStop() {
        val uses = conversation ?: return
        val taskId = state.value.confirmStop ?: return
        state.update { it.copy(confirmStop = null, stopping = it.stopping + taskId, notice = null) }
        viewModelScope.launch {
            val stopped = try {
                uses.control.stopTask(route.accountId, route.sessionId, taskId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(stopping = it.stopping - taskId, notice = SessionNotice.Text(error.userMessage())) }
                return@launch
            }
            if (!stopped) state.update { it.copy(stopping = it.stopping - taskId, notice = SessionNotice.NothingRunning) }
        }
    }

    /** A task report drops the stops it no longer lists: they ended. */
    private fun CloudSessionUiState.withTasks(event: TranscriptEvent): CloudSessionUiState {
        if (event !is TranscriptEvent.Tasks || stopping.isEmpty()) return this
        val live = event.tasks.map { it.id }.toSet()
        return copy(stopping = stopping.filterTo(mutableSetOf()) { it in live })
    }

    /** Switches the session's agent to [model], like `/model` in a terminal; the answer is the session with its new model. */
    fun setModel(model: String) = busy { uses -> changed(uses.control.setModel(route.accountId, route.sessionId, model)) }

    /** Switches the session's agent to [effort], like `/effort` in a terminal; the answer is the session with its new effort. */
    fun setEffort(effort: String) = busy { uses -> changed(uses.control.setEffort(route.accountId, route.sessionId, effort)) }

    private fun changed(session: CloudSession) {
        actions++
        state.update { it.copy(session = session) }
    }

    private fun busy(action: suspend (CloudConversationUseCases) -> Unit) {
        val uses = conversation ?: return
        if (state.value.busy) return
        state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                action(uses)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(notice = SessionNotice.Text(error.userMessage())) }
            } finally {
                state.update { it.copy(busy = false) }
            }
        }
    }

    private fun CloudSessionUiState.withTranscript(transcript: Transcript) =
        copy(transcript = transcript, pending = pending.reconciled(transcript.events)).withReconciledDrafts()

    /**
     * Drafts of requests that closed are dropped; an accepted answer that an `input` event rejected or cancelled (the
     * agent could not take it: the request stays open) goes back to editing, with the reason.
     */
    private fun CloudSessionUiState.withReconciledDrafts(): CloudSessionUiState {
        if (drafts.isEmpty()) return this
        val open = openRequests.mapNotNullTo(mutableSetOf()) { it.requestId }
        val refused = transcript.events.filterIsInstance<TranscriptEvent.InputChanged>().filter { it.status == "rejected" || it.status == "cancelled" }.associateBy { it.inputId }
        var notice = notice
        val kept = drafts.filterKeys { it in open }.mapValues { (_, draft) ->
            val refusal = draft.inputId?.let(refused::get)
            if (draft.state != AnswerState.SENT || refusal == null) draft
            else draft.copy(state = AnswerState.EDITING, sending = null, key = null, inputId = null).also { notice = SessionNotice.PermissionRejected(refusal.reason ?: refusal.status) }
        }
        return copy(drafts = kept, notice = notice)
    }

    // ---- Permission requests ---------------------------------------------------------------------------------------

    /** Picks an option: the only one of a single-choice question (leaving "Other"), or one more of a multi-select one. */
    fun selectOption(requestId: String, question: Int, option: Int) = edit(requestId) { card, draft ->
        val asked = card.request.questions.firstOrNull { it.index == question } ?: return@edit draft
        val choice = draft.choices[question] ?: QuestionChoice()
        val next = if (asked.multiSelect) choice.copy(options = if (option in choice.options) choice.options - option else choice.options + option)
        else choice.copy(options = setOf(option), other = false)
        draft.copy(choices = draft.choices + (question to next))
    }

    /** Picks "Other": exclusive in a single-choice question, toggled beside the options in a multi-select one. */
    fun selectOther(requestId: String, question: Int) = edit(requestId) { card, draft ->
        val asked = card.request.questions.firstOrNull { it.index == question } ?: return@edit draft
        val choice = draft.choices[question] ?: QuestionChoice()
        val next = if (asked.multiSelect) choice.copy(other = !choice.other) else choice.copy(options = emptySet(), other = true)
        draft.copy(choices = draft.choices + (question to next))
    }

    /** Typing in "Other" picks it. */
    fun onOtherText(requestId: String, question: Int, text: String) = edit(requestId) { card, draft ->
        val asked = card.request.questions.firstOrNull { it.index == question } ?: return@edit draft
        val choice = draft.choices[question] ?: QuestionChoice()
        val next = if (asked.multiSelect) choice.copy(other = true, otherText = text) else choice.copy(options = emptySet(), other = true, otherText = text)
        draft.copy(choices = draft.choices + (question to next))
    }

    /** Shows the next question once the current one is answered. */
    fun nextQuestion(requestId: String) = edit(requestId) { card, draft ->
        if (!card.currentAnswered || card.last) draft else draft.copy(step = card.step + 1)
    }

    /** Back to the previous question, its answer kept. */
    fun previousQuestion(requestId: String) = edit(requestId) { card, draft -> draft.copy(step = (card.step - 1).coerceAtLeast(0)) }

    /** On the last question: sends every answer in one request. */
    fun sendAnswers(requestId: String) {
        val card = cardFor(requestId) ?: return
        if (!card.editable || !card.answerable || !card.request.question || !card.last) return
        val answers = PermissionRules.answers(card.request.questions, card.draft.choices) ?: return
        deliverAnswer(card, PermissionAnswer(requestId, allow = true, answers = answers))
    }

    /** Allows a tool use. */
    fun allowTool(requestId: String) {
        val card = cardFor(requestId) ?: return
        if (!card.editable || !card.answerable || card.request.question) return
        deliverAnswer(card, PermissionAnswer(requestId, allow = true))
    }

    /**
     * "Bypass" on a tool use: records the owner's standing approval on the session, then allows this request too (the
     * server only answers the requests the agent asks after it). If the approval is refused, nothing is allowed and the
     * request stays open.
     */
    fun bypass(requestId: String) {
        val card = cardFor(requestId) ?: return
        if (!card.editable || !card.answerable || card.request.question || state.value.busy) return
        busy { uses ->
            changed(uses.control.setPermissionBypass(route.accountId, route.sessionId, true))
            cardFor(requestId)?.let { current -> if (current.editable) deliverAnswer(current, PermissionAnswer(requestId, allow = true)) }
        }
    }

    /** Takes the standing approval back: the next permission requests come to the member again. */
    fun revokeBypass() = busy { uses -> changed(uses.control.setPermissionBypass(route.accountId, route.sessionId, false)) }

    /** Declines a question or denies a tool use. */
    fun deny(requestId: String) {
        val card = cardFor(requestId) ?: return
        if (!card.editable) return
        deliverAnswer(card, PermissionAnswer(requestId, allow = false))
    }

    /** Sends a failed answer again, the same body under the same key: the server never applies it twice. */
    fun retryAnswer(requestId: String) {
        val card = cardFor(requestId) ?: return
        val answer = card.draft.sending ?: return
        if (card.draft.state != AnswerState.FAILED) return
        deliverAnswer(card, answer)
    }

    private fun cardFor(requestId: String): PermissionCard? = state.value.permission?.takeIf { it.requestId == requestId && requestId.isNotEmpty() }

    private fun edit(requestId: String, change: (PermissionCard, PermissionDraft) -> PermissionDraft) {
        val card = cardFor(requestId) ?: return
        if (!card.editable) return
        val edited = change(card, card.draft)
        if (edited == card.draft) return
        // Any change after a failure is a new answer: it gets a new key when sent.
        val next = if (edited.choices != card.draft.choices) edited.copy(state = AnswerState.EDITING, sending = null, key = null, reason = null) else edited
        state.update { it.copy(drafts = it.drafts + (requestId to next)) }
    }

    private fun deliverAnswer(card: PermissionCard, answer: PermissionAnswer) {
        val uses = conversation ?: return
        val requestId = card.requestId
        // The same answer keeps its key; another one (an edit, Deny after a failed Send) gets a new one.
        val key = card.draft.key?.takeIf { card.draft.sending == answer } ?: uses.answerPermission.key()
        val sending = card.draft.copy(state = AnswerState.SENDING, sending = answer, key = key, reason = null)
        state.update { it.copy(drafts = it.drafts + (requestId to sending), notice = null) }
        viewModelScope.launch {
            val outcome = uses.answerPermission(route.accountId, route.sessionId, answer, key)
            state.update { current ->
                val draft = current.drafts[requestId] ?: sending
                when (outcome) {
                    is AnswerOutcome.Accepted -> current.copy(drafts = current.drafts + (requestId to draft.copy(state = AnswerState.SENT, inputId = outcome.receipt.inputId)))
                    is AnswerOutcome.Retry -> current.copy(drafts = current.drafts + (requestId to draft.copy(state = AnswerState.FAILED, reason = outcome.reason)))
                    is AnswerOutcome.Closed -> current.copy(
                        drafts = current.drafts - requestId, closedRequests = current.closedRequests + requestId, notice = SessionNotice.PermissionClosed(outcome.code),
                    )
                    is AnswerOutcome.Invalid -> current.copy(drafts = current.drafts + (requestId to draft.edited()), notice = SessionNotice.PermissionInvalid(outcome.code))
                    is AnswerOutcome.Refused -> current.copy(
                        drafts = current.drafts + (requestId to draft.edited()),
                        notice = if (outcome.code in OWNER_ONLY) SessionNotice.PermissionDenied else SessionNotice.Text(refusal(outcome)),
                    )
                }.withReconciledDrafts()
            }
        }
    }

    /** A refusal in words: the server's error key when it is one, never its message. */
    private fun refusal(outcome: AnswerOutcome.Refused): Failure = (outcome.code?.let { AppException.Server(HTTP_FORBIDDEN, it) } ?: AppException.Server(HTTP_FORBIDDEN, null)).userMessage()

    private fun PermissionDraft.edited() = copy(state = AnswerState.EDITING, sending = null, key = null, reason = null)

    private suspend fun refreshSessionQuietly() {
        try {
            val asked = actions
            val session = getSession(route.accountId, route.sessionId)
            if (asked != actions) return
            state.update { it.copy(session = session, canControl = owns(session)) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The header stays as it was; the transcript goes on. An expired login was already signed out by the guard.
        }
    }

    private companion object {
        /** The console's and the brief's pace (TeamCode reads its header every 30 s). */
        const val HEADER_MILLIS = 10_000L
        const val AGENTS_MILLIS = 1_000L
        const val HTTP_FORBIDDEN = 403

        /** The `feed` value of the main agent's own events. */
        const val MAIN_FEED = "main"

        /** Only the owner answers (TeamCode's `permission_answer_denied`, Ostrom's `github_owner_only` and `permission_owner_only`). */
        val OWNER_ONLY = setOf("permission_answer_denied", "permission_owner_only", "github_owner_only")
    }
}
