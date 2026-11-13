// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/auth/AccountGuard.kt; see NOTICE.
package run.ostrom.app.domain.usecases.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import run.ostrom.app.domain.models.AppException

/**
 * A `401` from any call of an account: the account is checked once more ([RefreshAccountUseCase], which signs it out
 * only if the server refuses its token again), then the failure goes on to the screen. Never a retry loop.
 */
class AccountGuard(private val refreshAccount: RefreshAccountUseCase) {
    suspend operator fun <T> invoke(accountId: String, call: suspend () -> T): T = try {
        call()
    } catch (error: AppException.Unauthorized) {
        check(accountId)
        throw error
    }

    /** A stream ended with `401`, or with `denied` (the server also says so when the token expired): check the account. */
    fun <T> flow(accountId: String, flow: Flow<T>): Flow<T> = flow.catch { error ->
        if (error is AppException.Unauthorized) check(accountId)
        throw error
    }

    private suspend fun check(accountId: String) {
        try {
            refreshAccount(accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Offline or a refusing store: the account stays as it is, the screen shows the original failure.
        }
    }
}
