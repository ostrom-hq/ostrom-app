// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/repositories/RemoteArtifactRepository.kt; see NOTICE.
package run.ostrom.app.infrastructure.repositories

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Artifact
import run.ostrom.app.domain.models.ArtifactPage
import run.ostrom.app.domain.models.ArtifactTicket
import run.ostrom.app.domain.models.ArtifactVersion
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.repositories.ArtifactRepository
import run.ostrom.core.api.models.Artifact as ApiArtifact

/** The artifacts over the typed client (`/api/v1/artifacts`). */
class RemoteArtifactRepository(private val calls: AccountCalls) : ArtifactRepository {
    override suspend fun artifacts(accountId: String, before: String?): ArtifactPage = calls.withAccount(accountId) { client ->
        val page = client.artifacts.list(before = before, limit = PAGE)
        ArtifactPage(page.items.map { it.domain() }, page.next)
    }

    override suspend fun artifact(accountId: String, artifactId: String): Artifact =
        calls.withAccount(accountId, resource = true) { it.artifacts.get(artifactId).domain() }

    override suspend fun versions(accountId: String, artifactId: String): List<ArtifactVersion> = calls.withAccount(accountId, resource = true) { client ->
        client.artifacts.versions(artifactId).items.map { ArtifactVersion(it.number, millisOf(it.createdAt), it.createdBy, it.files.size) }.sortedByDescending { it.number }
    }

    /** A server without a content origin serves no page (`503 unavailable`): this server does not offer the viewer. */
    override suspend fun ticket(accountId: String, artifactId: String, version: Int?): ArtifactTicket = try {
        calls.withAccount(accountId, resource = true) { client ->
            val ticket = client.artifacts.ticket(artifactId, version)
            ArtifactTicket(ticket.artifactId, ticket.version, ticket.url, millisOf(ticket.expiresAt))
        }
    } catch (error: AppException.Server) {
        if (error.statusCode == 503 && error.code == UNAVAILABLE) throw AppException.Unsupported(Features.ARTIFACTS)
        throw error
    }

    private fun ApiArtifact.domain() = Artifact(
        id = id, title = title, owner = owner, access = access, relation = relation, latestVersion = latestVersion, url = url, updatedAt = millisOf(updatedAt),
    )

    private companion object {
        const val PAGE = 50
        const val UNAVAILABLE = "unavailable"
    }
}
