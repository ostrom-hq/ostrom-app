// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/repositories/ArtifactRepository.kt; see NOTICE.
package run.ostrom.app.domain.repositories

import run.ostrom.app.domain.models.Artifact
import run.ostrom.app.domain.models.ArtifactPage
import run.ostrom.app.domain.models.ArtifactTicket
import run.ostrom.app.domain.models.ArtifactVersion

/** The artifacts of an account's server (`artifacts.v1`). Failures are [run.ostrom.app.domain.models.AppException]s. */
interface ArtifactRepository {
    /** Newest first; [before] is the previous page's cursor. */
    suspend fun artifacts(accountId: String, before: String?): ArtifactPage
    suspend fun artifact(accountId: String, artifactId: String): Artifact
    /** Newest first. */
    suspend fun versions(accountId: String, artifactId: String): List<ArtifactVersion>
    /** A ticket for [version], the latest one when null. */
    suspend fun ticket(accountId: String, artifactId: String, version: Int?): ArtifactTicket
}
