// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/links/ResolveLinkUseCase.kt; see NOTICE.
package run.ostrom.app.domain.usecases.links

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.LinkRefusal
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.usecases.artifacts.ARTIFACT_ID
import run.ostrom.app.domain.usecases.auth.normalizeOrigin
import run.ostrom.app.domain.usecases.auth.trimTyped

/** An artifact to open in the viewer: [version] null for the latest. */
data class ArtifactLinkTarget(val accountId: String, val artifactId: String, val version: Int?)

/**
 * `ostrom://open?url=<artifact page>`, the link a chat app or a browser hands the app for an artifact's console page
 * (`<console>/artifacts/<id>[?v=<version>]`), as TeamCode's `teamcode://open?url=`.
 */
object ArtifactLinks {
    const val SCHEME = "ostrom://open?"

    /** Whether [link] is an `ostrom://open?` link, whatever it carries. */
    fun isOpenLink(link: String): Boolean = link.trimTyped().startsWith(SCHEME, ignoreCase = true)

    /** The page URL the link carries (`url=`, its only parameter), decoded; null when it carries none, two, or a malformed one. */
    fun urlOf(link: String): String? {
        val trimmed = link.trimTyped()
        if (!trimmed.startsWith(SCHEME, ignoreCase = true)) return null
        // The page is the link's only parameter, all of the rest: a sender that did not encode it keeps its own query
        // (`?utm=x&v=3`) whole. Its fragment, ignored, is cut either way.
        val query = trimmed.substring(SCHEME.length).substringBefore('#')
        if (!query.startsWith("url=")) return null
        val encoded = query.removePrefix("url=")
        // Given twice, which one wins would be a guess.
        if ("&url=" in encoded) return null
        return decode(encoded).orEmpty().trimTyped().takeIf { it.isNotEmpty() }
    }

    /** Percent-decoding of one query value; null for a malformed escape or bytes that are not UTF-8. */
    internal fun decode(value: String): String? {
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '%' -> {
                    if (i + 2 >= value.length) return null
                    val hex = value.substring(i + 1, i + 3)
                    // Two hexadecimal digits exactly: toIntOrNull alone would take a sign ("%-1").
                    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    bytes += hex.toInt(16).toByte()
                    i += 3
                }
                c == '+' -> { bytes += ' '.code.toByte(); i++ }
                else -> {
                    // The whole unescaped run at once, so a surrogate pair is encoded as one character.
                    val end = (i until value.length).firstOrNull { value[it] == '%' || value[it] == '+' } ?: value.length
                    value.substring(i, end).encodeToByteArray().forEach { bytes += it }
                    i = end
                }
            }
        }
        return try { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { null }
    }

    /** `<origin>/artifacts/<id>`, with an optional trailing slash and query; no credentials, no other path. */
    internal val VIEWER = Regex("""(https?://[^/?#@\s\\]+)/artifacts/([0-9a-f-]{36})/?(?:\?([^#\s]*))?""", RegexOption.IGNORE_CASE)
}

/**
 * Where an `ostrom://open?url=` link leads: the artifact of a signed-in account of this device, whose server is the
 * page's origin, or whose console is (`app.` for `api.`, as the sign-in pages are checked). That account becomes the
 * active one. Anything else is refused ([AppException.LinkRefused]) and nothing is opened or asked of any server.
 */
class OpenArtifactLinkUseCase(private val accounts: AccountRepository) {
    operator fun invoke(link: String): ArtifactLinkTarget {
        val url = ArtifactLinks.urlOf(link) ?: refuse(LinkRefusal.NOT_AN_ARTIFACT)
        val match = ArtifactLinks.VIEWER.matchEntire(url.substringBefore('#')) ?: refuse(LinkRefusal.NOT_AN_ARTIFACT)
        val origin = try {
            normalizeOrigin(match.groupValues[1])
        } catch (_: AppException.InvalidOrigin) {
            refuse(LinkRefusal.NOT_AN_ARTIFACT)
        }
        val artifactId = match.groupValues[2]
        if (!ARTIFACT_ID.matches(artifactId)) refuse(LinkRefusal.NOT_AN_ARTIFACT)
        // Other query parameters (a tracker added by a chat app) are ignored; `v` must be a version when present.
        val versions = match.groupValues[3].split('&').filter { it.startsWith("v=") }.map { it.removePrefix("v=") }
        if (versions.size > 1) refuse(LinkRefusal.NOT_AN_ARTIFACT)
        val version = versions.singleOrNull()?.let { v -> v.takeIf { it.length in 1..9 && it.all(Char::isDigit) }?.toInt()?.takeIf { it >= 1 } ?: refuse(LinkRefusal.NOT_AN_ARTIFACT) }
        val candidates = accounts.accounts.value.filter { origin == it.origin || origin == consoleOf(it.origin) }
        if (candidates.isEmpty()) refuse(LinkRefusal.UNKNOWN_SERVER)
        val signedIn = candidates.filter { it.signedIn }
        val account = signedIn.firstOrNull { it.id == accounts.activeAccountId.value } ?: signedIn.firstOrNull() ?: refuse(LinkRefusal.SIGNED_OUT)
        accounts.setActive(account.id)
        return ArtifactLinkTarget(account.id, artifactId, version)
    }

    private fun refuse(reason: LinkRefusal): Nothing = throw AppException.LinkRefused(reason)

    private companion object {
        /** The console of a server at `api.<domain>`: `app.<domain>`; null for any other server. */
        fun consoleOf(serverOrigin: String): String? = serverOrigin.takeIf { "://api." in it }?.replaceFirst("://api.", "://app.")
    }
}
