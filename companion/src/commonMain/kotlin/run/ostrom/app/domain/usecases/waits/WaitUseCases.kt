// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/waits/WaitUseCases.kt; see NOTICE.
package run.ostrom.app.domain.usecases.waits

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.FieldInput
import run.ostrom.app.domain.models.FieldType
import run.ostrom.app.domain.models.Wait
import run.ostrom.app.domain.models.WaitAction
import run.ostrom.app.domain.models.WaitInputProblem
import run.ostrom.app.domain.models.WaitInputProblem.Reason
import run.ostrom.app.domain.models.WaitPage
import run.ostrom.app.domain.models.WaitStatus
import run.ostrom.app.domain.models.initialInput
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.has
import run.ostrom.app.domain.repositories.WaitRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard

/** Whether the account's server has waits: the screen, its tab and the banner stay hidden otherwise. */
class WaitsAvailabilityUseCase(private val features: FeaturesRepository) {
    suspend operator fun invoke(accountId: String): Boolean = features.has(accountId, Features.WAITS)
}

/** One page of the member's waits in [status] (open by default), keyset-paged by [before]. */
class LoadWaitsUseCase(private val waits: WaitRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, status: WaitStatus = WaitStatus.OPEN, before: String? = null): WaitPage = guard(accountId) {
        if (!features.has(accountId, Features.WAITS)) throw AppException.Unsupported(Features.WAITS)
        waits.list(accountId, status, before)
    }
}

/** The open waits that resolve by themselves (a time, a webhook), soonest first. */
class LoadInProgressWaitsUseCase(private val waits: WaitRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String): List<Wait> = guard(accountId) {
        if (!features.has(accountId, Features.WAITS)) throw AppException.Unsupported(Features.WAITS)
        waits.inProgress(accountId)
    }
}

/** What happened to an answer. Never thrown except for cancellation. */
sealed interface WaitAnswerOutcome {
    data class Answered(val wait: Wait) : WaitAnswerOutcome
    /** Someone answered or cancelled it first (`wait_closed`), or it is gone: the list shows what is left. */
    data object Closed : WaitAnswerOutcome
    /** A field to fix, found here before sending anything. */
    data class Problem(val problem: WaitInputProblem) : WaitAnswerOutcome
    /** The server refused a value (`invalid_answer`). */
    data object Refused : WaitAnswerOutcome
    /** Offline or a busy server: the same answer can be sent again. */
    data object Retry : WaitAnswerOutcome
    data class Failed(val error: Exception) : WaitAnswerOutcome
}

class AnswerWaitUseCase(private val waits: WaitRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, wait: Wait, actionId: String, inputs: Map<String, FieldInput>): WaitAnswerOutcome {
        // An action the wait does not have (a card older than a reload): refused here, the wait stays where it is.
        val action = wait.actions.firstOrNull { it.id == actionId } ?: return WaitAnswerOutcome.Refused
        val values = try {
            WaitAnswerBuilder.build(wait, action, inputs)
        } catch (problem: WaitInputProblem) {
            return WaitAnswerOutcome.Problem(problem)
        }
        return try {
            WaitAnswerOutcome.Answered(guard(accountId) { waits.answer(accountId, wait.id, action.id, values) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: AppException.Unauthorized) {
            throw error
        } catch (error: AppException.Server) {
            when {
                error.statusCode == 409 || error.statusCode == 404 -> WaitAnswerOutcome.Closed
                error.statusCode == 400 -> WaitAnswerOutcome.Refused
                error.statusCode >= 500 || error.statusCode == 408 -> WaitAnswerOutcome.Retry
                else -> WaitAnswerOutcome.Failed(error)
            }
        } catch (error: AppException.NotFound) {
            WaitAnswerOutcome.Closed
        } catch (error: AppException.Unreachable) {
            WaitAnswerOutcome.Retry
        } catch (error: AppException.RateLimited) {
            WaitAnswerOutcome.Retry
        } catch (error: Exception) {
            WaitAnswerOutcome.Failed(error)
        }
    }
}

/** What the waits screen does next: load again, or show the connection's state. */
sealed interface WaitsSignal {
    data object Reload : WaitsSignal
    data class Connection(val reconnecting: Boolean) : WaitsSignal
}

/**
 * Keeps the waits current. TeamCode follows its event feed here; Ostrom's server does not serve `GET /api/v1/events`
 * yet, so the waits load once, then again on return to the screen and on refresh. The feed plugs in here (a reload
 * when it opens, after a gap, and at each event about this member's waits) once the server serves it.
 */
class FollowWaitsUseCase(private val features: FeaturesRepository, private val guard: AccountGuard) {
    operator fun invoke(accountId: String): Flow<WaitsSignal> = guard.flow(accountId, flow {
        if (features.has(accountId, Features.WAITS)) emit(WaitsSignal.Reload)
    })
}

/** The answer's JSON values from what was typed; checks what can be checked before sending (the server checks the rest). */
object WaitAnswerBuilder {
    fun build(wait: Wait, action: WaitAction, inputs: Map<String, FieldInput>): Map<String, JsonElement> {
        val values = linkedMapOf<String, JsonElement>()
        for (field in wait.fields) {
            if (field.type == FieldType.INFO) continue
            val needed = field.required || field.id in action.requires
            fun problem(reason: Reason) = WaitInputProblem(field.id, field.label, reason)
            if (field.type == FieldType.FILE) {
                // A file is attached from the session.
                if (needed) throw problem(Reason.UNSUPPORTED)
                continue
            }
            val input = inputs[field.id] ?: field.initialInput()
            val value: JsonElement? = when (field.type) {
                FieldType.SECRET -> (input as? FieldInput.Secret)?.value?.takeIf { it.isNotBlank() }?.let { text ->
                    if (text.encodeToByteArray().size > MAX_SECRET_BYTES || '\u0000' in text) throw problem(Reason.TOO_LONG)
                    JsonPrimitive(text)
                }
                // A required box must be checked (a consent); an optional one sends its yes or its no.
                FieldType.CONFIRM -> (input as? FieldInput.Flag)?.takeIf { it.value || !needed }?.let { JsonPrimitive(it.value) }
                FieldType.CHOICE -> (input as? FieldInput.Chosen)?.values?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { chosen ->
                    if (field.multiple) JsonArray(chosen.map(::JsonPrimitive)) else JsonPrimitive(chosen.first())
                }
                FieldType.NUMBER -> (input as? FieldInput.Text)?.value?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                    text.toLongOrNull()?.let(::JsonPrimitive) ?: text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: throw problem(Reason.NOT_A_NUMBER)
                }
                FieldType.DATE -> (input as? FieldInput.Text)?.value?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                    if (!isDate(text)) throw problem(Reason.NOT_A_DATE)
                    JsonPrimitive(text)
                }
                else -> (input as? FieldInput.Text)?.value?.takeIf { it.isNotBlank() }?.let { text ->
                    if (text.length > (field.maxLength ?: DEFAULT_TEXT)) throw problem(Reason.TOO_LONG)
                    JsonPrimitive(text)
                }
            }
            if (value == null) { if (needed) throw problem(Reason.MISSING) else continue }
            values[field.id] = value
        }
        return values
    }

    private const val DEFAULT_TEXT = 8_000
    private const val MAX_SECRET_BYTES = 4096
    private val DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")

    private fun isDate(text: String): Boolean {
        val (year, month, day) = DATE.matchEntire(text)?.destructured ?: return false
        val m = month.toInt(); val d = day.toInt(); val y = year.toInt()
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return m in 1..12 && d in 1..days[m - 1]
    }
}
