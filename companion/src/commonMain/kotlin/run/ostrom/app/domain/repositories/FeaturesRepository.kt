// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/FeaturesRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.ServerCapabilities

/**
 * What an account's server announces in `GET /capabilities`, kept in memory and read again on [refresh]. The keys are
 * those of ostrom-core's `ServerFeatures`; a key the app does not know is ignored.
 */
interface FeaturesRepository {
    suspend fun capabilities(accountId: String, refresh: Boolean = false): ServerCapabilities

    /** The last capabilities read for the account, or null when none were read yet (offline since the start). */
    fun cached(accountId: String): ServerCapabilities?
}

/** Whether the account's server announces [feature]. */
suspend fun FeaturesRepository.has(accountId: String, feature: String): Boolean = feature in capabilities(accountId).features
