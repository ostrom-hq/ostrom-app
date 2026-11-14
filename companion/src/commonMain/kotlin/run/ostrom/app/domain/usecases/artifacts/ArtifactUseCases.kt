// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/artifacts/ArtifactUseCases.kt; see NOTICE.
package run.ostrom.app.domain.usecases.artifacts

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Artifact
import run.ostrom.app.domain.models.ArtifactPage
import run.ostrom.app.domain.models.ArtifactTicket
import run.ostrom.app.domain.models.ArtifactVersion
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.repositories.ArtifactRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.has
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.normalizeOrigin

/** An artifact id as the server writes it. */
val ARTIFACT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** A page of the artifacts the person can read; [AppException.Unsupported] from a server without them (the tab is hidden then). */
class LoadArtifactsUseCase(private val artifacts: ArtifactRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, before: String? = null): ArtifactPage = guard(accountId) {
        if (!features.has(accountId, Features.ARTIFACTS)) throw AppException.Unsupported(Features.ARTIFACTS)
        artifacts.artifacts(accountId, before)
    }
}

/** An artifact, its versions (newest first), and its console page when it is a web link: the link the app shares. */
data class ArtifactDetails(val artifact: Artifact, val versions: List<ArtifactVersion>, val viewerUrl: String?)

class GetArtifactUseCase(private val artifacts: ArtifactRepository, private val guard: AccountGuard) {
    /**
     * Ostrom's console lives on its own origin, which the app does not know: the page the server names is opened and
     * shared only when it is a plain `http` or `https` link ([isWebLink]).
     */
    suspend operator fun invoke(accountId: String, artifactId: String): ArtifactDetails = guard(accountId) {
        if (!ARTIFACT_ID.matches(artifactId)) throw AppException.NotFound()
        val artifact = artifacts.artifact(accountId, artifactId)
        ArtifactDetails(artifact, artifacts.versions(accountId, artifactId), artifact.url.takeIf(::isWebLink))
    }

    /** The artifact alone, to see whether a newer version was published. */
    suspend fun current(accountId: String, artifactId: String): Artifact = guard(accountId) { artifacts.artifact(accountId, artifactId) }
}

/**
 * A checked ticket, and the directory the viewer may navigate in ([prefix]); [html] is the demo's page held in memory,
 * with an empty [prefix]: nothing navigates in it.
 */
data class OpenedArtifact(val ticket: ArtifactTicket, val prefix: String, val html: String? = null)

/**
 * A ticket to show one version in the app. Ostrom serves the pages on a content origin of its own
 * (`<content origin>/a/<id>/<ticket>/<entry>`), which the server does not announce: the content URL must have that
 * shape, for this artifact, on an `https` origin (or `http` on loopback) without credentials, and its
 * `/a/<id>/<ticket>/` directory is then the only place the viewer may navigate ([contentPrefix]).
 */
class OpenArtifactUseCase(private val artifacts: ArtifactRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, artifactId: String, version: Int?): OpenedArtifact = guard(accountId) {
        if (!ARTIFACT_ID.matches(artifactId)) throw AppException.NotFound()
        if (!features.has(accountId, Features.ARTIFACTS)) throw AppException.Unsupported(Features.ARTIFACTS)
        val ticket = artifacts.ticket(accountId, artifactId, version)
        if (ticket.artifactId != artifactId || (version != null && ticket.version != version)) throw AppException.Server(200, "invalid_ticket")
        val html = ticket.inlineHtml
        if (html != null) {
            OpenedArtifact(ticket, prefix = "", html = html)
        } else {
            OpenedArtifact(ticket, contentPrefix(artifactId, ticket.contentUrl) ?: throw AppException.Server(200, "invalid_ticket"))
        }
    }

    companion object {
        private val TICKET = Regex("[A-Za-z0-9_.~-]{1,1024}")

        /**
         * `<origin>/a/<id>/<ticket>/` when [contentUrl] is a page under it, with no query, fragment, `..` or escaped
         * character in the ticket; null otherwise.
         */
        fun contentPrefix(artifactId: String, contentUrl: String): String? {
            // The server spells its content origin as configured (case, an explicit default port): compared, and kept,
            // in the canonical form, the one web views report the pages they navigate to in.
            val canonical = canonical(contentUrl) ?: return null
            val origin = canonical.substringBefore("://") + "://" + canonical.substringAfter("://").substringBefore('/')
            val base = "$origin/a/$artifactId/"
            if (!canonical.startsWith(base)) return null
            val ticket = canonical.removePrefix(base).substringBefore('/')
            if (!TICKET.matches(ticket) || ticket.all { it == '.' }) return null
            val prefix = "$base$ticket/"
            return prefix.takeIf { canonical.startsWith(it) && allows(it, canonical) }
        }

        /** Whether the viewer may load [url] for the ticket's [prefix]: a page under it, never a path that climbs out. */
        fun allows(prefix: String, url: String): Boolean {
            if (prefix.isEmpty()) return false
            val canonical = canonical(url) ?: return false
            if (!canonical.startsWith(prefix)) return false
            val path = canonical.removePrefix(prefix).substringBefore('#').substringBefore('?').lowercase()
            // Dots, slashes and backslashes, escaped or not, once or twice (`%252e`); a plain escaped percent is a file name.
            val once = path.replace("%25", "%")
            return path.split('/').none { it == ".." || it == "." } && listOf("%2e", "%2f", "%5c").none { it in once } && '\\' !in path
        }

        /** [url] with its origin normalized (lower-case host, default port dropped); null without a valid web origin. */
        private fun canonical(url: String): String? {
            val schemeEnd = url.indexOf("://").takeIf { it > 0 } ?: return null
            val authorityEnd = url.indexOf('/', schemeEnd + 3).takeIf { it > 0 } ?: return null
            val served = url.substring(0, authorityEnd)
            if ('@' in served) return null
            val origin = try { normalizeOrigin(served) } catch (_: AppException.InvalidOrigin) { return null }
            return origin + url.substring(authorityEnd)
        }
    }
}

/**
 * An absolute `http` or `https` URL with a host, without control characters, nor format characters (bidirectional
 * overrides, zero-width marks) that would make the URL shown for confirmation read differently from the one opened.
 */
fun isWebLink(uri: String): Boolean {
    if (uri.any { it.isISOControl() || it.isWhitespace() || it.category == CharCategory.FORMAT }) return false
    val scheme = uri.substringBefore("://", "").lowercase()
    if (scheme != "http" && scheme != "https") return false
    val host = uri.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    return host.isNotEmpty()
}
