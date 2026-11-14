// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/PushRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

/** This installation as a push destination of an account's server (`POST` and `DELETE /api/v1/push-devices`). */
interface PushRepository {
    /** Registers (or registers again) the phone for the account, in its organization. */
    suspend fun register(accountId: String, token: String, publicKey: String, platform: String, locale: String?)

    /** Stops the account's server pushing to this phone: the device this process registered, if any. */
    suspend fun clear(accountId: String)
}
