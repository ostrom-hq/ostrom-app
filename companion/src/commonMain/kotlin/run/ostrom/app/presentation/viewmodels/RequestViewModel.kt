// SPDX-License-Identifier: Apache-2.0
// The React Native app's useRequestViewModel.ts, shaped like TeamCode's companion view models (Apache-2.0); see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Approval
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.ApprovalReading
import run.ostrom.app.domain.models.DecisionProblem
import run.ostrom.app.domain.models.NoApprove
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.find
import run.ostrom.app.domain.usecases.approvals.AnswerApprovalUseCase
import run.ostrom.app.domain.usecases.approvals.AnswerResult
import run.ostrom.app.domain.usecases.approvals.GetApprovalUseCase
import run.ostrom.app.presentation.navigation.RequestRoute

/**
 * One request and the person's answer.
 *
 * @property approval The request as last read from the server.
 * @property pinned What the person first read (or read again): the answer is bound to it.
 * @property sending The answer being confirmed or sent, to show which button is busy.
 * @property consoleUrl The console's page of the request (`<origin>/yes/<id>`), null once the account is gone.
 * @property consoleFailed Opening the console failed: its address is shown, to copy.
 */
data class RequestUiState(
    val approval: Approval? = null,
    val pinned: ApprovalReading? = null,
    val loading: Boolean = true,
    val error: Failure? = null,
    val orgRequired: Boolean = false,
    val sending: ApprovalAnswer? = null,
    val problem: DecisionProblem? = null,
    val declining: Boolean = false,
    val reason: String = "",
    val consoleUrl: String? = null,
    val consoleFailed: Boolean = false,
    val copied: Boolean = false,
) {
    /** What the screen shows is no longer what the person read: no answer goes until they read it again. */
    val changed: Boolean get() = approval != null && pinned != null && approval.reading != pinned

    /** The problem said under the request; a change always wins. */
    val shownProblem: DecisionProblem? get() = if (changed) DecisionProblem.CHANGED else problem

    val open: Boolean get() = approval?.open == true
    val askedByMe: Boolean get() = approval?.viewer?.self == true
    val noApprove: NoApprove? get() = approval?.noApprove
    val mayApprove: Boolean get() = approval?.mayApprove == true
}

/**
 * The request of [route], read when the screen opens and each time it is shown again ([load]). The answer is bound to
 * the reading the person first saw: a refresh that changes it (digest, who, what, gate) holds every answer until they
 * say they read it again ([reread]). A yes always unlocks for this request; a no needs its reason.
 */
class RequestViewModel(
    private val route: RequestRoute,
    private val get: GetApprovalUseCase,
    private val answer: AnswerApprovalUseCase,
    private val accounts: AccountRepository,
) : ViewModel() {
    private val state = MutableStateFlow(RequestUiState(consoleUrl = consoleUrl()))
    val uiState: StateFlow<RequestUiState> = state.asStateFlow()
    private var reading: Job? = null

    init {
        load()
    }

    /** Reads the request again; never while an answer is on its way (its own result is read after it). */
    fun load() {
        if (state.value.sending != null || reading?.isActive == true) return
        reading = viewModelScope.launch { read() }
    }

    private suspend fun read() {
        state.update { it.copy(loading = true) }
        try {
            val approval = get(route.accountId, route.id)
            state.update { it.copy(approval = approval, pinned = it.pinned ?: approval.reading, loading = false, error = null, orgRequired = false) }
        } catch (error: CancellationException) {
            state.update { it.copy(loading = false) }
            throw error
        } catch (error: Exception) {
            state.update { it.copy(loading = false, error = error.userMessage(), orgRequired = error is AppException.OrganizationRequired, consoleFailed = false) }
        }
    }

    /** The person read the request as it is now: answers are bound to this reading. */
    fun reread() {
        state.update { current -> current.copy(pinned = current.approval?.reading ?: current.pinned, problem = null, consoleFailed = false) }
    }

    fun startDecline() = state.update { it.copy(declining = true) }

    fun cancelDecline() = state.update { it.copy(declining = false) }

    fun setReason(reason: String) = state.update { it.copy(reason = reason) }

    /** Says yes, after the device's unlock for this request; [prompt] is what the biometric prompt says. */
    fun approve(prompt: String) = send(ApprovalAnswer.APPROVE, null, prompt)

    /** Says no with the reason typed; nothing goes without one. */
    fun decline(prompt: String) = send(ApprovalAnswer.REJECT, state.value.reason, prompt)

    private fun send(choice: ApprovalAnswer, note: String?, prompt: String) {
        val current = state.value
        val approval = current.approval ?: return
        if (current.sending != null || current.changed || current.pinned == null) return
        if (choice == ApprovalAnswer.REJECT && note.isNullOrBlank()) return
        reading?.cancel()
        state.update { it.copy(sending = choice, problem = null, consoleFailed = false) }
        viewModelScope.launch {
            val result = try {
                answer(route.accountId, approval, choice, note, prompt)
            } finally {
                state.update { it.copy(sending = null) }
            }
            when (result) {
                is AnswerResult.Answered -> state.update {
                    it.copy(approval = result.approval, pinned = result.approval.reading, declining = false, reason = "", problem = null)
                }
                AnswerResult.NothingSent -> Unit
                is AnswerResult.Failed -> {
                    state.update { it.copy(problem = result.problem) }
                    // The answer may have gone through, or the request changed: it is read again.
                    if (result.problem !in UNSENT) load()
                }
            }
        }
    }

    /** Opening the console failed (no browser, a refused link): its address is shown to copy. */
    fun consoleNotOpened() = state.update { it.copy(consoleFailed = true, copied = false) }

    fun consoleOpened() = state.update { it.copy(consoleFailed = false) }

    fun linkCopied() = state.update { it.copy(copied = true) }

    private fun consoleUrl(): String? = accounts.find(route.accountId)?.takeIf { it.signedIn }?.let { "${it.origin}/yes/${encodePathSegment(route.id)}" }

    private companion object {
        /** Problems of an unlock that failed before the answer left: nothing to read again. */
        val UNSENT = setOf(
            DecisionProblem.BIOMETRICS_UNAVAILABLE, DecisionProblem.BIOMETRICS_FAILED, DecisionProblem.UNLOCK_REFUSED, DecisionProblem.UNLOCK_OFFLINE,
        )
    }
}

/** [value] as one path segment: unreserved characters kept, every other byte of its UTF-8 percent-encoded. */
internal fun encodePathSegment(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val char = (byte.toInt() and 0xFF).toChar()
        if (char.isLetterOrDigit() && char.code < 128 || char in "-_.~") append(char)
        else append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
    }
}

private const val HEX = "0123456789ABCDEF"
