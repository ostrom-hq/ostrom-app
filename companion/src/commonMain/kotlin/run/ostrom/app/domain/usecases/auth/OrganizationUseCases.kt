// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.domain.usecases.auth

import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.repositories.find

/**
 * The organization an account's calls act in (`Ostrom-Org`), chosen among the person's memberships: a person in
 * several gets `409 org_required` until they choose. [list] reads them; [choose] keeps the choice on the device.
 */
class OrganizationUseCases(private val auth: AuthRepository, private val accounts: AccountRepository, private val guard: AccountGuard) {
    suspend fun list(accountId: String): List<Organization> = guard(accountId) { auth.organizations(accountId) }.sortedBy { it.name.lowercase() }

    fun choose(accountId: String, organization: Organization): Account? {
        val account = accounts.find(accountId) ?: return null
        val chosen = account.copy(organizationId = organization.id, organizationName = organization.name)
        if (chosen != account) accounts.update(chosen)
        return chosen
    }
}
