// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/RemoteWaitRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import run.ostrom.app.domain.models.FieldType
import run.ostrom.app.domain.models.Wait
import run.ostrom.app.domain.models.WaitAction
import run.ostrom.app.domain.models.WaitAnswer
import run.ostrom.app.domain.models.WaitField
import run.ostrom.app.domain.models.WaitKind
import run.ostrom.app.domain.models.WaitOption
import run.ostrom.app.domain.models.WaitPage
import run.ostrom.app.domain.models.WaitRef
import run.ostrom.app.domain.models.WaitResolver
import run.ostrom.app.domain.models.WaitSource
import run.ostrom.app.domain.models.WaitStatus
import run.ostrom.app.domain.repositories.WaitRepository
import run.ostrom.core.api.models.WaitStatus as ApiWaitStatus
import run.ostrom.core.api.models.Wait as ApiWait
import run.ostrom.core.api.models.WaitAction as ApiWaitAction
import run.ostrom.core.api.models.WaitField as ApiWaitField
import run.ostrom.core.api.models.WaitRef as ApiWaitRef

/** The waits over the typed client (`/api/v1/waits`), one request per call. */
class RemoteWaitRepository(private val calls: AccountCalls) : WaitRepository {
    override suspend fun list(accountId: String, status: WaitStatus, before: String?): WaitPage = calls.withAccount(accountId) { client ->
        // What a person answers: the waits of this member, resolved by a human.
        val page = client.waits.list(status = status.wire, mine = true, before = before, limit = PAGE, resolver = "human")
        WaitPage(page.items.map { it.domain() }, page.next)
    }

    override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait =
        calls.withAccount(accountId, resource = true) { it.waits.answer(waitId, action, values).domain() }

    override suspend fun inProgress(accountId: String): List<Wait> = calls.withAccount(accountId) { client ->
        // Two bounded reads: the server lists one resolver at a time besides "all", which would page through people's waits too.
        (client.waits.list(status = "open", mine = true, limit = PAGE, resolver = "time").items +
            client.waits.list(status = "open", mine = true, limit = PAGE, resolver = "event").items)
            .map { it.domain() }.sortedWith(compareBy<Wait> { it.dueOrTimeout ?: Long.MAX_VALUE }.thenBy { it.id })
    }

    private companion object { const val PAGE = 50 }
}

/** An ISO instant in milliseconds; an unreadable one (a newer server's format) reads as unknown. */
internal fun millisOf(iso: String?): Long? = iso?.let {
    try {
        Instant.parse(it).toEpochMilliseconds()
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun ApiWait.domain() = Wait(
    id = id,
    source = WaitSource.WAIT,
    kind = WaitKind.of(kind),
    open = status == ApiWaitStatus.OPEN,
    sessionId = sessionId,
    sessionName = null,
    requestId = null,
    assigneeId = assignee,
    title = question.title,
    body = question.body,
    refs = question.refs.map { it.domain() },
    fields = question.fields.map { it.domain() },
    actions = question.actions.map { it.domain() },
    createdAt = millisOf(createdAt) ?: 0L,
    cancelled = status == ApiWaitStatus.CANCELLED,
    resolvedAt = millisOf(resolvedAt),
    answer = answer?.let { WaitAnswer(it.action, it.values, it.author, millisOf(it.answeredAt)) },
    resolver = WaitResolver.of(resolver ?: WaitResolver.HUMAN.wire),
    dueAt = millisOf(dueAt),
    timeoutAt = millisOf(timeoutAt),
    eventSource = eventSource,
)

private fun ApiWaitRef.domain() = WaitRef(kind, id, url, title)
private fun ApiWaitAction.domain() = WaitAction(id, label, requires, style)
// Ostrom's choice options are their own labels.
private fun ApiWaitField.domain() = WaitField(
    id, FieldType.of(type), label, help, required, default, options.map { WaitOption(it, it) }, multiple, allowOther, maxLength,
)
