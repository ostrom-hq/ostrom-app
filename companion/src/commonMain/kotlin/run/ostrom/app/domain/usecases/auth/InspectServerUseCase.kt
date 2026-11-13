// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.domain.usecases.auth

import run.ostrom.app.domain.models.ServerInfo
import run.ostrom.app.domain.repositories.AuthRepository

/** The typed origin, normalized, checked to answer like an Ostrom server before anything is sent to it. */
class InspectServerUseCase(private val auth: AuthRepository) {
    suspend operator fun invoke(input: String): ServerInfo = auth.discover(normalizeOrigin(input))
}
