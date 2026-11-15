// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/sessions/SessionActionUseCases.kt; see NOTICE.
package run.ostrom.app.domain.usecases.sessions

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.CloudSessionProblem
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.FilePolicy
import run.ostrom.app.domain.models.InputReceipt
import run.ostrom.app.domain.models.PendingMessage
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.UploadedFile
import run.ostrom.app.domain.repositories.CloudSessionRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.announced
import run.ostrom.app.domain.services.IdGenerator
import run.ostrom.app.domain.usecases.auth.AccountGuard

/** The server's message rules (`POST …/messages`), checked before sending so a refusal never costs a request. */
object MessageRules {
    const val MAX_CHARS = 32_000

    fun valid(text: String): Boolean =
        text.isNotBlank() && text.length <= MAX_CHARS && text.none { it.isISOControl() && it != '\n' && it != '\t' && it != '\r' }

    /** A message carrying files may have no text at all. */
    fun valid(text: String, withFiles: Boolean): Boolean = (withFiles && text.isEmpty()) || valid(text)
}

/**
 * Sends a message, waking a paused session (`runners.cloud.conversation.v1`). The [PendingMessage.clientMessageId] is
 * the idempotency key: the caller keeps it to retry the same message after a network failure, and the server answers a
 * retry with the first receipt.
 */
class SendMessageUseCase(
    private val sessions: CloudSessionRepository,
    private val features: FeaturesRepository,
    private val guard: AccountGuard,
    private val ids: IdGenerator,
) {
    /**
     * A new message to send; an empty or overlong [text] is refused before any request. [files]: uploaded files the
     * message carries; with files, [text] may be empty.
     */
    fun draft(text: String, files: List<UploadedFile> = emptyList()): PendingMessage {
        require(files.size <= FilePolicy.MAX_FILES) { "A message carries ${FilePolicy.MAX_FILES} files at most." }
        val sent = if (files.isNotEmpty() && text.isBlank()) "" else text
        if (!MessageRules.valid(sent, withFiles = files.isNotEmpty())) throw AppException.CloudSessionInvalid(CloudSessionProblem.MESSAGE)
        return PendingMessage(ids.newId(), sent, PendingMessage.State.SENDING, files = files)
    }

    /** Sends [message] (a new draft or a failed one being retried); never throws except for cancellation. */
    suspend operator fun invoke(accountId: String, sessionId: String, message: PendingMessage): PendingMessage = try {
        val receipt = guard(accountId) {
            val announced = features.announced(accountId)
            if (Features.RUNNERS_CLOUD_CONVERSATION !in announced) throw AppException.Unsupported(Features.RUNNERS_CLOUD_CONVERSATION)
            if (message.files.isNotEmpty() && Features.RUNNERS_CLOUD_FILES !in announced) throw AppException.Unsupported(Features.RUNNERS_CLOUD_FILES)
            sessions.send(accountId, sessionId, message.text, message.clientMessageId, message.files.map { it.id })
        }
        message.received(receipt)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: AppException.Server) {
        // A server failure may pass; a refusal (an invalid message, an ended session) would be refused again.
        val transient = error.statusCode >= 500 || error.statusCode == 408
        message.copy(state = if (transient) PendingMessage.State.FAILED else PendingMessage.State.REFUSED, reason = error.code ?: error.message)
    } catch (error: AppException.NotFound) {
        message.copy(state = PendingMessage.State.REFUSED, reason = error.message)
    } catch (error: AppException.Unsupported) {
        message.copy(state = PendingMessage.State.REFUSED, reason = error.message)
    } catch (error: Exception) {
        message.copy(state = PendingMessage.State.FAILED, reason = error.message)
    }
}

internal fun PendingMessage.received(receipt: InputReceipt): PendingMessage = copy(
    state = if (receipt.status == "rejected" || receipt.status == "cancelled") PendingMessage.State.REFUSED else PendingMessage.State.SENT,
    inputId = receipt.inputId,
    woke = receipt.woke,
    reason = receipt.reason,
)

/**
 * The messages still shown as pending, given the transcript: one whose `message.user` event arrived is dropped (the
 * transcript shows it), one an `input` event rejected or cancelled is marked refused with the event's reason.
 */
fun List<PendingMessage>.reconciled(events: List<TranscriptEvent>): List<PendingMessage> {
    if (isEmpty()) return this
    // Recorded as a message, or consumed by the agent: a message too large for the event cap is recorded only as a size
    // marker without its input id, but its input is still marked consumed.
    val recorded = events.mapNotNullTo(mutableSetOf()) { (it as? TranscriptEvent.UserMessage)?.inputId } +
        events.mapNotNull { (it as? TranscriptEvent.InputChanged)?.takeIf { e -> e.status == "consumed" }?.inputId }
    val refused = events.filterIsInstance<TranscriptEvent.InputChanged>().filter { it.status == "rejected" || it.status == "cancelled" }.associateBy { it.inputId }
    return mapNotNull { message ->
        val id = message.inputId
        when {
            id == null -> message
            id in recorded -> null
            id in refused -> message.copy(state = PendingMessage.State.REFUSED, reason = refused.getValue(id).reason ?: refused.getValue(id).status)
            else -> message
        }
    }
}

/**
 * What a conversation session's screen offers beyond its transcript, each where the server announces it: the permission
 * cards and the waiting state, Bypass, the model and effort pickers, background tasks and subagent feeds.
 */
data class ConversationOptions(
    val conversation: Boolean = false,
    val permissions: Boolean = false,
    val bypass: Boolean = false,
    val model: Boolean = false,
    val effort: Boolean = false,
    val tasks: Boolean = false,
    val feeds: Boolean = false,
)

/** Interrupt, stop a background task, Bypass, the model and the effort of a conversation session. An interrupt with nothing running is not a failure. */
class ControlConversationUseCase(private val sessions: CloudSessionRepository, private val guard: AccountGuard, private val features: FeaturesRepository) {
    /** What the server announces; nothing when it cannot be read (each part stays hidden). */
    suspend fun options(accountId: String): ConversationOptions = try {
        val announced = features.announced(accountId)
        ConversationOptions(
            conversation = Features.RUNNERS_CLOUD_CONVERSATION in announced,
            permissions = Features.RUNNERS_CLOUD_PERMISSIONS in announced,
            bypass = Features.RUNNERS_CLOUD_PERMISSIONS_BYPASS in announced,
            model = Features.RUNNERS_CLOUD_MODEL in announced,
            effort = Features.RUNNERS_CLOUD_EFFORT in announced,
            tasks = Features.RUNNERS_CLOUD_TASKS in announced,
            feeds = Features.RUNNERS_CLOUD_TRANSCRIPT_FEEDS in announced,
        )
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        ConversationOptions()
    }

    /**
     * Returns false when there was nothing to interrupt. Ostrom's interrupt takes no idempotency key (TeamCode's does):
     * the screen keeps one interrupt in flight at a time instead.
     */
    suspend fun interrupt(accountId: String, sessionId: String): Boolean = guard(accountId) {
        require(Features.RUNNERS_CLOUD_CONVERSATION)(accountId)
        try {
            sessions.interrupt(accountId, sessionId)
            true
        } catch (error: AppException.Server) {
            if (error.statusCode == 409 && error.code == NOTHING_TO_INTERRUPT) false else throw error
        }
    }

    /** Stops the background task [taskId]; false when the session no longer runs an agent (nothing to stop). */
    suspend fun stopTask(accountId: String, sessionId: String, taskId: String): Boolean = guard(accountId) {
        require(Features.RUNNERS_CLOUD_TASKS)(accountId)
        try {
            sessions.stopTask(accountId, sessionId, taskId)
            true
        } catch (error: AppException.Server) {
            // Ostrom's server answers a stop with no running agent as it answers an interrupt (TeamCode: `nothing_to_stop`).
            if (error.statusCode == 409 && (error.code == NOTHING_TO_INTERRUPT || error.code == NOTHING_TO_STOP)) false else throw error
        }
    }

    /** Approves every later tool use of the session in advance, or takes that back; the server refuses anyone but the owner. */
    suspend fun setPermissionBypass(accountId: String, sessionId: String, enabled: Boolean): CloudSession = guard(accountId) {
        require(Features.RUNNERS_CLOUD_PERMISSIONS_BYPASS)(accountId)
        sessions.setPermissionBypass(accountId, sessionId, enabled)
    }

    /** Switches the agent to [model]; the id is checked here as the server checks it. */
    suspend fun setModel(accountId: String, sessionId: String, model: String): CloudSession = guard(accountId) {
        val sent = model.trim()
        if (!validModel(sent)) throw AppException.CloudSessionInvalid(CloudSessionProblem.MODEL)
        require(Features.RUNNERS_CLOUD_MODEL)(accountId)
        sessions.setModel(accountId, sessionId, sent)
    }

    /** Switches the agent to [effort]; the value is checked here as the server checks it (it has no policy for effort). */
    suspend fun setEffort(accountId: String, sessionId: String, effort: String): CloudSession = guard(accountId) {
        val sent = effort.trim()
        if (!validEffort(sent)) throw AppException.CloudSessionInvalid(CloudSessionProblem.EFFORT)
        require(Features.RUNNERS_CLOUD_EFFORT)(accountId)
        sessions.setEffort(accountId, sessionId, sent)
    }

    private fun require(feature: String): suspend (String) -> Unit = { accountId ->
        if (feature !in features.announced(accountId)) throw AppException.Unsupported(feature)
    }

    companion object {
        const val NOTHING_TO_INTERRUPT = "nothing_to_interrupt"
        const val NOTHING_TO_STOP = "nothing_to_stop"
        const val MAX_MODEL = 120
        private val MODEL = Regex("[a-zA-Z0-9._:/-]+")

        /** The server's model rules (as at creation): 1 to [MAX_MODEL] letters, digits and `. _ : / -`. */
        fun validModel(model: String): Boolean = model.length in 1..MAX_MODEL && MODEL.matches(model)

        /** The server's effort rules (as at creation): the same shape as a model id. */
        fun validEffort(effort: String): Boolean = validModel(effort)

        /** What the effort picker suggests for [agent] (`claude` or `codex`), lowest first; any other valid value can be typed. */
        fun effortLevels(agent: String): List<String> = if (agent == "codex") listOf("low", "medium", "high", "xhigh") else listOf("low", "medium", "high", "xhigh", "max")

        /** Bypass, the model and the effort are the owner's: a role opens nobody else's session. */
        fun canControl(memberId: String?, session: CloudSession): Boolean = memberId != null && session.ownerId == memberId

        /** The model and the effort change while the session is not over. */
        fun canChangeModel(session: CloudSession): Boolean = !session.status.ended

        /** Messages go to a session that is not over nor changing owner; a paused one wakes for them. */
        fun canMessage(session: CloudSession): Boolean = !session.status.ended && session.status != run.ostrom.app.domain.models.CloudSessionStatus.TRANSFERRING
    }
}
