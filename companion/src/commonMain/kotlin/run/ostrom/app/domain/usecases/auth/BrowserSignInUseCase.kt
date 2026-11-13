// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.domain.usecases.auth

import kotlin.io.encoding.Base64
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.ServerInfo
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.services.BrowserSignIn
import run.ostrom.app.domain.services.SecureRandom

/**
 * A phone's GitHub sign-in: the system's browser session opens the server's `/auth/github/start` with a PKCE
 * challenge and a state of this attempt, and comes back to [REDIRECT_URI] with a one-time code, exchanged for an app
 * token and its unlock key. Only the latest attempt connects: an earlier one's return, even with its own valid state,
 * connects nothing (the former app's AUDIT-SECU-APP-2 A4). Returns null when the person closed the browser.
 */
class BrowserSignInUseCase(
    private val auth: AuthRepository,
    private val browser: BrowserSignIn,
    private val random: SecureRandom,
    private val complete: CompleteSignInUseCase,
) {
    private var awaited: String? = null

    suspend operator fun invoke(server: ServerInfo): Account? {
        if (!server.offersGitHub) throw AppException.NoGitHubSignIn()
        val state = base64Url(random.nextBytes(STATE_BYTES))
        awaited = state
        val attempt = auth.prepareBrowserSignIn(server.origin, REDIRECT_URI, state)
        val returned = browser.authenticate(attempt.url, SCHEME)
        // Cleared by its own return, whatever it is; a later attempt may have replaced it already.
        val asked = awaited == state
        if (asked) awaited = null
        returned ?: return null
        val query = queryOfReturn(returned)
        if (query == null || !asked) throw AppException.SignInMismatch()
        val code = query["code"]
        if (code.isNullOrEmpty()) {
            // An invite-only instance names its refusal; only with the state of this attempt.
            throw if (query["error"] == "signup_closed" && query["state"] == state) AppException.SignUpClosed() else AppException.SignInDenied()
        }
        if (query["state"] != state) throw AppException.SignInMismatch()
        val session = auth.exchangeCode(server.origin, code, attempt.codeVerifier, REDIRECT_URI)
        return complete(server, session, SignInMethod.BROWSER)
    }

    companion object {
        const val SCHEME = "ostrom"

        /** The only `redirect_uri` the server returns a mobile sign-in to, compared as a whole string. */
        const val REDIRECT_URI = "ostrom://auth/callback"
        private const val STATE_BYTES = 16
    }
}

/** Base64url without padding (RFC 4648 section 5), the encoding of OAuth's `state`. */
fun base64Url(bytes: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

/** The query of a return to [BrowserSignInUseCase.REDIRECT_URI]; null for any other address. */
internal fun queryOfReturn(url: String): Map<String, String>? {
    val end = url.indexOfAny(charArrayOf('?', '#')).takeIf { it >= 0 } ?: url.length
    if (url.substring(0, end) != BrowserSignInUseCase.REDIRECT_URI) return null
    val query = url.substring(end).removePrefix("?").substringBefore('#')
    if (query.isEmpty()) return emptyMap()
    return query.split('&').filter { it.isNotEmpty() }.associate { pair ->
        val name = pair.substringBefore('=')
        val value = if ('=' in pair) pair.substringAfter('=') else ""
        percentDecode(name) to percentDecode(value)
    }
}

/** `application/x-www-form-urlencoded` decoding; a malformed escape is kept as it is. */
internal fun percentDecode(value: String): String {
    val bytes = mutableListOf<Byte>()
    var index = 0
    while (index < value.length) {
        val char = value[index]
        val hex = if (char == '%' && index + 2 < value.length) value.substring(index + 1, index + 3).toIntOrNull(16) else null
        when {
            char == '+' -> { bytes += ' '.code.toByte(); index++ }
            hex != null -> { bytes += hex.toByte(); index += 3 }
            else -> { bytes += char.toString().encodeToByteArray().toList(); index++ }
        }
    }
    return bytes.toByteArray().decodeToString()
}
