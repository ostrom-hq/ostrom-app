// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/.../demo/DemoContent.kt (waits), DemoMode.kt (answerWait),
// DemoRepositories.kt (DemoWaitRepository) and DemoRouting.kt (DemoAwareWaitRepository); see NOTICE.
package run.ostrom.app.demo

import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.FieldType
import run.ostrom.app.domain.models.Wait
import run.ostrom.app.domain.models.WaitAction
import run.ostrom.app.domain.models.WaitAnswer
import run.ostrom.app.domain.models.WaitField
import run.ostrom.app.domain.models.WaitKind
import run.ostrom.app.domain.models.WaitOption
import run.ostrom.app.domain.models.WaitPage
import run.ostrom.app.domain.models.WaitResolver
import run.ostrom.app.domain.models.WaitSource
import run.ostrom.app.domain.models.WaitStatus
import run.ostrom.app.domain.repositories.WaitRepository
import run.ostrom.app.domain.services.Clock

private const val MINUTE = 60_000L

/** The demo's waits, relative to [now]: what agents ask Ada, what she answered or saw withdrawn, and what a timer or a CI resolves. */
internal fun demoWaits(now: Long): List<Wait> {
    val login = DemoContent.account().login
    fun wait(id: String, kind: WaitKind, title: String, body: String?, minutesAgo: Int, fields: List<WaitField> = emptyList(), actions: List<WaitAction> = emptyList()) = Wait(
        id, WaitSource.WAIT, kind, true, "s-$id", null, null, login, title, body, emptyList(), fields, actions, now - minutesAgo * MINUTE,
    )
    val review = listOf(WaitAction("approve", "Approve", style = "primary"), WaitAction("request_changes", "Request changes", requires = listOf("feedback")))
    val feedback = WaitField("feedback", FieldType.TEXT, "Feedback")
    return listOf(
        wait("w1", WaitKind.HUMAN_REQUEST, "Review the migration plan",
            "Three steps: add `/v2/invoices`, map `/v1` onto it, then remove `/v1` after the clients update. Does the order suit you?", 4, listOf(feedback), review),
        wait("w2", WaitKind.TURN_FAILED, "The last turn failed",
            "The agent stopped after the provider rejected the request. You can ask it to try again.", 21,
            listOf(WaitField("note", FieldType.TEXT, "Note for the agent")), listOf(WaitAction("retry", "Try again", style = "primary"), WaitAction("dismiss", "Dismiss"))),
        wait("w3", WaitKind.HUMAN_REQUEST, "Which store do we ship first?", null, 35,
            listOf(WaitField("store", FieldType.CHOICE, "Store", required = true, options = listOf(WaitOption("App Store", "App Store"), WaitOption("Google Play", "Google Play")))),
            listOf(WaitAction("done", "Send"))),
        wait("w4", WaitKind.HUMAN_REQUEST, "Is the tone right for new developers?", "I drafted the first page in a friendly, direct voice.", 180, listOf(feedback), review)
            .copy(open = false, resolvedAt = now - 150 * MINUTE, answer = WaitAnswer("approve", mapOf("feedback" to JsonPrimitive("Yes, keep it short.")), login, now - 150 * MINUTE)),
        wait("w5", WaitKind.HUMAN_REQUEST, "Pick the filter layout", null, 240, actions = listOf(WaitAction("done", "Done")))
            .copy(open = false, cancelled = true, resolvedAt = now - 230 * MINUTE),
        // What the server watches and wakes the agent for: nobody is asked, so these are "in progress", not "waiting for you".
        wait("w6", WaitKind.TIMED, "Check the nightly deploy", null, 12).copy(resolver = WaitResolver.TIME, dueAt = now + 3 * 60 * MINUTE),
        wait("w7", WaitKind.TIMED, "CI of the cache pull request", null, 26)
            .copy(resolver = WaitResolver.EVENT, eventSource = "webhook", onTimeout = "wake", timeoutAt = now + 20 * 60 * MINUTE),
    )
}

/** The demo's waits: an open one is answered at once, as the server would (no agent wakes). */
class DemoWaitRepository(private val demo: DemoMode, private val clock: Clock) : WaitRepository {
    override suspend fun list(accountId: String, status: WaitStatus, before: String?): WaitPage {
        val all = demo.store().waits.value.filter { it.status == status && it.resolver == WaitResolver.HUMAN }
        return WaitPage(if (before == null) all.sortedWith(compareByDescending<Wait> { it.createdAt }.thenByDescending { it.id }) else emptyList(), null)
    }

    override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait {
        val waits = demo.store().waits
        val wait = waits.value.firstOrNull { it.id == waitId } ?: throw AppException.NotFound()
        if (!wait.open) throw AppException.Server(409, "wait_closed")
        if (wait.actions.none { it.id == action }) throw AppException.Server(400, "invalid_answer")
        val at = clock.nowMillis()
        val resolved = wait.copy(open = false, resolvedAt = at, answer = WaitAnswer(action, values, wait.assigneeId, at))
        waits.update { all -> all.map { if (it.id == waitId) resolved else it } }
        return resolved
    }

    override suspend fun inProgress(accountId: String): List<Wait> =
        demo.store().waits.value.filter { it.open && it.resolver != WaitResolver.HUMAN }.sortedWith(compareBy<Wait> { it.dueOrTimeout ?: Long.MAX_VALUE }.thenBy { it.id })
}

/** The demo account's waits from memory, every other account's from its server. */
class DemoAwareWaitRepository(private val real: WaitRepository, private val demo: WaitRepository) : WaitRepository {
    private fun of(accountId: String) = if (isDemo(accountId)) demo else real
    override suspend fun list(accountId: String, status: WaitStatus, before: String?) = of(accountId).list(accountId, status, before)
    override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>) = of(accountId).answer(accountId, waitId, action, values)
    override suspend fun inProgress(accountId: String) = of(accountId).inProgress(accountId)
}
