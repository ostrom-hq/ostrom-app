// SPDX-License-Identifier: AGPL-3.0-only
// The polling follows ostrom-cli's DeviceLogin (`ostrom login`); see NOTICE.
package run.ostrom.app.domain.usecases.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DeviceLogin
import run.ostrom.app.domain.models.ServerInfo
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.repositories.DevicePoll

/**
 * A computer's sign-in, the device flow of `ostrom login`: [start] gives the code and the page to enter it on (GitHub's,
 * or one of the server's own origin, checked by the repository), [await] asks the server every interval until the
 * person said yes, the code expired or they said no. A lost connection, a `5xx` or a `429` is asked again until the
 * expiry: the person may already have entered the code.
 */
class DeviceSignInUseCase(
    private val auth: AuthRepository,
    private val complete: CompleteSignInUseCase,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun start(server: ServerInfo): DeviceLogin {
        if (!server.offersGitHub) throw AppException.NoGitHubSignIn()
        return auth.startDeviceLogin(server)
    }

    suspend fun await(login: DeviceLogin): Account {
        var waited = 0L
        var pause = login.intervalMillis
        var lost: Exception? = null
        while (waited < login.expiresInMillis) {
            sleep(pause)
            waited += pause
            val poll = try {
                auth.pollDeviceLogin(login.server.origin, login.deviceCode).also { lost = null }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!error.isTransient()) throw error
                lost = error
                null
            }
            when (poll) {
                null -> Unit
                is DevicePoll.Pending -> if (poll.slowDown) pause += SLOW_DOWN_MILLIS
                is DevicePoll.Done -> return complete(login.server, poll.session, SignInMethod.DEVICE)
            }
        }
        throw lost ?: AppException.SignInExpired()
    }

    private fun Exception.isTransient(): Boolean = this is AppException.Unreachable || this is AppException.RateLimited ||
        (this is AppException.Server && statusCode >= 500)

    private companion object {
        const val SLOW_DOWN_MILLIS = 5_000L
    }
}
