// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/auth/RefreshAccountUseCase.kt; see NOTICE.
package run.ostrom.app.domain.usecases.auth

import kotlinx.coroutines.CancellationException
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.services.Clock
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.unlockKeyEntry

/**
 * Re-reads the person behind an account (`GET /auth/me`). An expired token, a missing one or a `401` (the session
 * revoked in the console, the token expired) marks the account signed out and forgets its secrets, once: the app then
 * asks to sign in again. Other failures (offline) leave the account as it is and are thrown.
 */
class RefreshAccountUseCase(
    private val auth: AuthRepository,
    private val accounts: AccountRepository,
    private val credentials: CredentialStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(accountId: String): Account? {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return null
        if (!account.signedIn) return account
        val token = try {
            credentials.read(accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A locked or refusing secure store: the token may well be there, so the account stays signed in.
            throw AppException.CredentialStorage()
        }
        val expired = account.expiresAt?.let { it <= clock.nowMillis() } ?: false
        if (expired || token == null) return signOutLocally(account)
        val member = try {
            auth.me(accountId)
        } catch (_: AppException.Unauthorized) {
            // Only if nothing signed the account in again while the call was in flight (its session would be new).
            val current = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return null
            return if (current.signedIn && current.expiresAt == account.expiresAt) signOutLocally(current) else current
        }
        val current = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return null
        if (!current.signedIn) return current
        val updated = current.copy(login = member.login)
        if (updated != current) accounts.update(updated)
        return updated
    }

    private suspend fun signOutLocally(account: Account): Account {
        val current = accounts.accounts.value.firstOrNull { it.id == account.id } ?: return account.copy(signedIn = false)
        if (current.expiresAt != account.expiresAt || !current.signedIn) return current
        val signedOut = current.copy(signedIn = false)
        accounts.update(signedOut)
        auth.release(account.id)
        // The token is dead on the server either way; a store refusing the delete must not keep the account signed in.
        for (entry in listOf(account.id, unlockKeyEntry(account.id))) {
            try { credentials.delete(entry) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
        }
        return signedOut
    }
}
