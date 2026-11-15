// SPDX-License-Identifier: Apache-2.0
// The demo of TeamCode's companion (Apache-2.0), companion/.../demo/DemoContent.kt and DemoRepositories.kt, with
// Ostrom's conversation session; see NOTICE.
package run.ostrom.app.demo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import run.ostrom.app.domain.models.AgentQuestion
import run.ostrom.app.domain.models.AgentQuestionOption
import run.ostrom.app.domain.models.BackgroundTask
import run.ostrom.app.domain.models.KnownAgent
import run.ostrom.app.domain.models.KnownAgentState
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.PermissionAnswer
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TranscriptPage
import run.ostrom.app.domain.models.TurnPhase
import run.ostrom.app.domain.repositories.TranscriptRepository

/** What the demo agent answers to any message. */
internal const val DEMO_REPLY = "Noted. In this demo nothing runs: on a real server your agent would work on it now."

private const val SUBAGENT = "t-explore"

/**
 * The demo's conversation, relative to [startedAt]: a question, the agent reading the code with a subagent, a background
 * command still running, and a permission request waiting for Ada.
 */
internal fun demoConversation(startedAt: Long): List<TranscriptEvent> {
    val at = startedAt - 18 * 60_000L
    return listOf(
        TranscriptEvent.Status(1, at, "claude", "claude-opus", null),
        TranscriptEvent.UserMessage(2, at + 1_000, "Compute the Bernoulli numbers up to B8 on the engine, and check them against Ada's note G.", "demo-in-1", DemoContent.MEMBER_ID),
        TranscriptEvent.Turn(3, at + 2_000, TurnPhase.STARTED, null, null),
        TranscriptEvent.AgentMessage(4, at + 5_000, "I'll have a subagent read the mill's code while I set up the computation.", null),
        TranscriptEvent.ToolCall(
            5, at + 6_000, SUBAGENT, "Agent", "{\n  \"subagent_type\": \"Explore\",\n  \"description\": \"Read the mill\",\n  \"prompt\": \"How does the mill carry?\"\n}",
            "Read the mill", null,
        ),
        TranscriptEvent.ToolCall(6, at + 7_000, "t-read", "Read", "{\n  \"file_path\": \"engine/Mill.kt\"\n}", "engine/Mill.kt", SUBAGENT),
        TranscriptEvent.ToolResult(7, at + 8_000, "t-read", "class Mill { fun carry(tens: Int) { ... } }", false, null, SUBAGENT),
        TranscriptEvent.AgentMessage(8, at + 20_000, "The mill carries the tens in one pass, anticipating the carriage.", SUBAGENT),
        TranscriptEvent.ToolResult(9, at + 21_000, SUBAGENT, "The mill carries the tens in one pass, anticipating the carriage.", false, null, null),
        TranscriptEvent.ToolCall(10, at + 30_000, "t-tables", "Bash", "{\n  \"command\": \"./gradlew :engine:tables --continuous\"\n}", "./gradlew :engine:tables --continuous", null),
        TranscriptEvent.Tasks(
            11, at + 31_000,
            listOf(BackgroundTask("task-tables", "local_bash", "./gradlew :engine:tables --continuous", null, "t-tables", at + 31_000)),
            stoppable = true,
        ),
        TranscriptEvent.AgentMessage(
            12, at + 40_000,
            "The tables run in the background. Here is the plan for B8:\n\n```kotlin\nval b8 = bernoulli(8) // -1/30\n```\n\nI need to push the results to a branch.", null,
        ),
        TranscriptEvent.PermissionRequest(
            13, at + 41_000, "perm-push", "Bash", question = false,
            input = "{\n  \"command\": \"git push origin bernoulli\"\n}", description = "Push the results to the bernoulli branch",
        ),
    )
}

/** A question the demo agent asks after a message that mentions a choice, so the question card can be tried. */
private fun demoQuestion(seq: Long, at: Long) = TranscriptEvent.PermissionRequest(
    seq, at, "perm-question-$seq", "AskUserQuestion", question = true,
    questions = listOf(
        AgentQuestion(0, "Which note should the results follow?", "Note", listOf(AgentQuestionOption("Note G", "Ada's own"), AgentQuestionOption("Note A", null)), false),
    ),
    input = "{}",
)

/** Records the member's [text] and the agent's scripted reply, as one finished turn; returns the input id of the message. */
internal fun DemoStore.exchange(sessionId: String, text: String, at: Long): String {
    val inputId = "demo-input-$sessionId-${(transcripts.value[sessionId]?.size ?: 0) + 1}"
    append(sessionId) { seq ->
        buildList {
            add(TranscriptEvent.UserMessage(seq(), at, text, inputId, DemoContent.MEMBER_ID))
            add(TranscriptEvent.Turn(seq(), at, TurnPhase.STARTED, null, null))
            add(TranscriptEvent.AgentMessage(seq(), at, DEMO_REPLY, null))
            if ("choose" in text.lowercase()) add(demoQuestion(seq(), at)) else add(TranscriptEvent.Turn(seq(), at, TurnPhase.COMPLETED, 0, 1))
        }
    }
    return inputId
}

/** An interrupt ends the turn under way. */
internal fun DemoStore.interrupted(sessionId: String, at: Long): String {
    append(sessionId) { seq -> listOf(TranscriptEvent.Turn(seq(), at, TurnPhase.INTERRUPTED, null, null)) }
    return "demo-interrupt-$at"
}

/** An answer is consumed at once; an allowed push finishes the turn. */
internal fun DemoStore.answered(sessionId: String, answer: PermissionAnswer, at: Long): String {
    val inputId = "demo-answer-${answer.requestId}"
    append(sessionId) { seq ->
        listOf(
            TranscriptEvent.InputChanged(seq(), at, inputId, "consumed", null, answer.requestId),
            TranscriptEvent.AgentMessage(seq(), at, if (answer.allow) "Done." else "Understood, I won't.", null),
            TranscriptEvent.Turn(seq(), at, TurnPhase.COMPLETED, 0, 1),
        )
    }
    return inputId
}

/** A stopped task leaves the next report. */
internal fun DemoStore.stoppedTask(sessionId: String, taskId: String, at: Long): String {
    val live = transcripts.value[sessionId].orEmpty().filterIsInstance<TranscriptEvent.Tasks>().lastOrNull()
    append(sessionId) { seq -> listOf(TranscriptEvent.Tasks(seq(), at, live?.tasks.orEmpty().filterNot { it.id == taskId }, stoppable = true)) }
    return "demo-stop-$taskId"
}

private fun DemoStore.append(sessionId: String, events: (seq: () -> Long) -> List<TranscriptEvent>) {
    val all = transcripts.value
    var last = all[sessionId]?.lastOrNull()?.seq ?: 0
    transcripts.value = all + (sessionId to all[sessionId].orEmpty() + events { ++last })
}

/** The demo's transcripts, live: what a message adds shows at once. */
class DemoTranscriptRepository(private val demo: DemoMode) : TranscriptRepository {
    override suspend fun page(accountId: String, sessionId: String, before: Long?): TranscriptPage = feedPage(accountId, sessionId, before, null)

    override suspend fun feedPage(accountId: String, sessionId: String, before: Long?, feed: String?): TranscriptPage {
        val events = demo.store().transcripts.value[sessionId].orEmpty()
        val shown = when (feed) {
            null -> events
            "main" -> events.filter { it.parent() == null }
            else -> events.filter { it.parent() == feed }
        }
        return TranscriptPage(if (before == null) shown else emptyList(), null, events.lastOrNull()?.seq ?: 0)
    }

    override suspend fun agents(accountId: String, sessionId: String): List<KnownAgent> {
        val events = demo.store().transcripts.value[sessionId].orEmpty()
        val call = events.filterIsInstance<TranscriptEvent.ToolCall>().firstOrNull { it.id == SUBAGENT } ?: return emptyList()
        val result = events.filterIsInstance<TranscriptEvent.ToolResult>().firstOrNull { it.id == SUBAGENT }
        val steps = events.count { it is TranscriptEvent.ToolCall && it.parentToolUseId == SUBAGENT }
        return listOf(KnownAgent(SUBAGENT, "Explore", "Read the mill", if (result == null) KnownAgentState.RUNNING else KnownAgentState.COMPLETED, call.at, result?.at, steps, null, null))
    }

    override fun follow(accountId: String, sessionId: String, after: Long): Flow<LiveUpdate<TranscriptEvent>> = flow {
        val store = demo.store()
        emit(LiveUpdate.Opened(true))
        var last = after
        val watched = combine(store.transcripts, store.cloudSessions) { transcripts, sessions -> transcripts[sessionId].orEmpty() to sessions.firstOrNull { it.id == sessionId } }
        // Like the server's stream: every event once, in order, then `Ended` once the session ended.
        emitAll(watched.transformWhile { (events, session) ->
            for (event in events.filter { it.seq > last }) {
                emit(LiveUpdate.Item(event))
                last = event.seq
            }
            val ended = session?.status?.ended == true
            if (ended) emit(LiveUpdate.Ended)
            !ended
        })
    }

    private fun TranscriptEvent.parent(): String? = when (this) {
        is TranscriptEvent.AgentMessage -> parentToolUseId
        is TranscriptEvent.ToolCall -> parentToolUseId
        is TranscriptEvent.ToolResult -> parentToolUseId
        else -> null
    }
}

/** The demo account's transcripts from memory, every other account's from its server. */
class DemoAwareTranscriptRepository(private val real: TranscriptRepository, private val demo: TranscriptRepository) : TranscriptRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun page(accountId: String, sessionId: String, before: Long?) = of(accountId).page(accountId, sessionId, before)
    override suspend fun feedPage(accountId: String, sessionId: String, before: Long?, feed: String?) = of(accountId).feedPage(accountId, sessionId, before, feed)
    override suspend fun agents(accountId: String, sessionId: String) = of(accountId).agents(accountId, sessionId)
    override fun follow(accountId: String, sessionId: String, after: Long) = of(accountId).follow(accountId, sessionId, after)
}
