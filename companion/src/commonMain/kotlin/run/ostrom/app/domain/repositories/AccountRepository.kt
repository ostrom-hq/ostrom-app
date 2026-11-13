// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/AccountRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import kotlinx.coroutines.flow.StateFlow
import run.ostrom.app.domain.models.Account

/** The accounts known on this device (metadata only) and the one the app shows. */
interface AccountRepository {
    val accounts: StateFlow<List<Account>>
    val activeAccountId: StateFlow<String?>

    /** Adds [account] or replaces the one with the same id, and makes it active. */
    fun save(account: Account)
    fun update(account: Account)
    /** Removes the account; the active one moves to another account, or to none. */
    fun remove(id: String)
    fun setActive(id: String)
}

/** The account with [id], or null. */
fun AccountRepository.find(id: String): Account? = accounts.value.firstOrNull { it.id == id }
