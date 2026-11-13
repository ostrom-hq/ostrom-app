// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/auth/SignOutUseCase.kt; see NOTICE.
package run.ostrom.app.domain.usecases.auth

import kotlinx.coroutines.CancellationException
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.services.CredentialStore
import run.ostrom.app.domain.services.unlockKeyEntry

/**
 * Revokes the session on the server, then forgets the token, the unlock key and the account. Offline (or refused),
 * the local sign-out still happens; the result says the server session was not revoked, and it expires on its own.
 * [beforeLogout] runs first, while the session can still speak (this phone stops taking push).
 */
class SignOutUseCase(
    private val auth: AuthRepository,
    private val accounts: AccountRepository,
    private val credentials: CredentialStore,
    private val beforeLogout: suspend (accountId: String) -> Unit = {},
) {
    /** Whether the server session is gone, and if not, why. */
    enum class Result { REVOKED, SERVER_UNREACHABLE, STORAGE_REFUSED }

    suspend operator fun invoke(accountId: String): Result {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return Result.REVOKED
        if (account.signedIn) {
            try {
                beforeLogout(accountId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Best effort: the sign-out goes on.
            }
        }
        var result = if (!account.signedIn) Result.REVOKED else try {
            auth.logout(accountId)
            Result.REVOKED
        } catch (error: CancellationException) {
            throw error
        } catch (_: AppException.Unauthorized) {
            Result.REVOKED // The server no longer knows this session: nothing left to revoke.
        } catch (_: AppException.CredentialStorage) {
            Result.STORAGE_REFUSED // The token could not even be read to revoke it.
        } catch (_: Exception) {
            Result.SERVER_UNREACHABLE
        }
        for (entry in listOf(accountId, unlockKeyEntry(accountId))) {
            try {
                credentials.delete(entry)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (result != Result.REVOKED) result = Result.STORAGE_REFUSED
            }
        }
        auth.release(accountId)
        accounts.remove(accountId)
        return result
    }
}
