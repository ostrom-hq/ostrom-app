// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation.navigation

import kotlinx.coroutines.CancellationException
import run.ostrom.app.domain.models.CloudSession
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.has

/** Whether the capabilities last read for the account announce sessions; false when none were read yet. */
internal fun FeaturesRepository.opensSessions(accountId: String): Boolean {
    val capabilities = cached(accountId) ?: return false
    return Features.SESSIONS in capabilities.features
}

/** Read again when nothing was cached yet (a cold start from the notification); offline, what was cached decides. */
internal suspend fun FeaturesRepository.servesSessions(accountId: String): Boolean = try {
    has(accountId, Features.SESSIONS)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    opensSessions(accountId)
}

/** The transfer of [session] to its owner's choice, or null for a session with no owner to give it from. */
internal fun transferRouteOf(accountId: String, session: CloudSession): TransferRoute? =
    session.ownerId?.let { TransferRoute(accountId, session.id, it) }
