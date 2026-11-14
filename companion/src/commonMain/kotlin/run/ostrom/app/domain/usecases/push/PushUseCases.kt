// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/push/PushUseCases.kt and ReadPushUseCase.kt; see NOTICE.
package run.ostrom.app.domain.usecases.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.distinctUntilChanged
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.PushNotice
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.AuthRepository
import run.ostrom.app.domain.repositories.FeaturesRepository
import run.ostrom.app.domain.repositories.PushRepository
import run.ostrom.app.domain.repositories.find
import run.ostrom.app.domain.repositories.has
import run.ostrom.app.domain.services.PushPlatform
import run.ostrom.app.domain.usecases.auth.normalizeOrigin

/**
 * Keeps every signed-in account's server told where to push (`POST /api/v1/push-devices`): at each start of the app,
 * when FCM rotates the token, when an account signs in and when its organization changes (a phone is registered in
 * one organization). Each server receives the same token and key; a notification names its server, organization and
 * member, so a phone signed in to several accounts tells them apart: the account's member id is read once it is
 * registered, and the registration counts only with it. The accounts register side by side, so a server that does not
 * answer delays no other; a failed one is tried again after [retryMillis], doubled each time up to an hour, and at the
 * next change of the token or the accounts.
 */
class SyncPushUseCase(
    private val accounts: AccountRepository,
    private val features: FeaturesRepository,
    private val push: PushRepository,
    private val auth: AuthRepository,
    private val platform: PushPlatform,
    /** Accounts left out: the demo account, which has no server. */
    private val skip: (String) -> Boolean = { false },
    private val retryMillis: Long = 60_000,
) {
    private data class Registration(val accountId: String, val organizationId: String?, val token: String)

    private enum class Outcome { REGISTERED, NOT_OFFERED, FAILED }

    /** Runs until cancelled. */
    suspend operator fun invoke() {
        // Touched by one pass at a time: collectLatest cancels and joins a pass before the next starts.
        val done = mutableSetOf<Registration>()
        combine(platform.token, accounts.accounts) { token, list ->
            token to list.filter { it.signedIn && !skip(it.id) }.map { it.id to it.organizationId }.toSet()
        }
            .distinctUntilChanged()
            .collectLatest { (token, signedIn) ->
                if (token == null) return@collectLatest
                val wanted = signedIn.map { (id, organization) -> Registration(id, organization, token) }.toSet()
                done.retainAll(wanted)
                var wait = retryMillis
                while (true) {
                    val outcomes = coroutineScope {
                        (wanted - done).map { registration -> async { registration to register(registration.accountId, token) } }.awaitAll()
                    }
                    // A server without push is asked again at the next change: its capabilities may have been stale.
                    done += outcomes.filter { it.second == Outcome.REGISTERED }.map { it.first }
                    if (outcomes.none { it.second == Outcome.FAILED }) break
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(MAX_RETRY_MILLIS)
                }
            }
    }

    private suspend fun register(accountId: String, token: String): Outcome = try {
        if (!features.has(accountId, Features.NOTIFICATIONS_PUSH)) {
            Outcome.NOT_OFFERED
        } else {
            push.register(accountId, token, platform.publicKey(), platform.platform, pushLocale(platform.locale))
            if (readMember(accountId)) Outcome.REGISTERED else Outcome.FAILED
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Outcome.FAILED
    }

    /** Whether the account has its member id, which its pushes name: read when it was not yet. */
    private suspend fun readMember(accountId: String): Boolean {
        if (accounts.find(accountId)?.memberId != null) return true
        val member = auth.me(accountId)
        val current = accounts.find(accountId) ?: return false
        val memberId = member.memberId ?: return false
        // Only for the organization the call acted in: one chosen meanwhile has another membership.
        if (!current.signedIn || member.organization?.id != current.organizationId) return false
        if (current.memberId == null) accounts.update(current.copy(memberId = memberId))
        return true
    }

    private companion object {
        const val MAX_RETRY_MILLIS = 3_600_000L
    }
}

/**
 * The phone's language as the server takes it (`fr`, `fr-FR`, `zh-Hans-CN`): a tag with extensions (`ar-EG-u-nu-latn`)
 * keeps its language and region, anything else is left out rather than refusing the whole registration.
 */
internal fun pushLocale(tag: String?): String? {
    val parts = tag?.trim()?.split('-', '_')
        ?.takeIf { it.first().length in 2..3 && it.first().all(Char::isLetter) && !it.first().equals(UNDETERMINED, ignoreCase = true) }
        ?: return null
    val kept = listOf(parts.first()) + parts.drop(1).takeWhile { it.length in 2..8 && it.all(Char::isLetterOrDigit) }.take(2)
    return kept.joinToString("-").takeIf { LOCALE.matches(it) }
}

// BCP 47's "no language": nothing to word the notifications in.
private const val UNDETERMINED = "und"

// The server's pattern (ostrom-server RegisterPushDeviceUseCase).
private val LOCALE = Regex("[A-Za-z]{2,3}([_-][A-Za-z0-9]{2,8}){0,2}")

/**
 * Before a sign-out: the server stops pushing to this phone for the account. Best effort, and at most [timeoutMillis]:
 * an unreachable server does not hold the sign-out for a second timeout.
 */
class ClearPushUseCase(private val push: PushRepository, private val timeoutMillis: Long = 5_000) {
    suspend operator fun invoke(accountId: String) {
        try {
            withTimeoutOrNull(timeoutMillis) { push.clear(accountId) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The server also forgets the phones of a session it revokes.
        }
    }
}

/**
 * Opens a push received by the Android messaging service: decrypts the payload with the device key and finds the
 * signed-in account it is for, by server, organization and member. Null when it cannot be read (another key, a damaged
 * payload) or when no signed-in account matches: a push for an account signed out on this phone is not shown.
 */
class ReadPushUseCase(private val accounts: AccountRepository, private val platform: PushPlatform) {
    suspend operator fun invoke(payload: String): PushNotice? {
        val push = try {
            platform.open(payload)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        val account = accounts.accounts.value.accountFor(push.origin, push.orgId, push.memberId) ?: return null
        val title = push.title.take(MAX_TITLE).ifBlank { return null }
        // An id the app would refuse is left out: the tap still opens the account.
        val link = NotificationLinks.link(
            push.origin, push.orgId, push.memberId,
            approvalId = push.approvalId?.takeIf(NotificationLinks::isId),
            sessionId = push.sessionId?.takeIf(NotificationLinks::isId),
            waitId = push.waitId?.takeIf(NotificationLinks::isId),
        )
        return PushNotice(push.id, account.id, title, push.body.take(MAX_BODY), link)
    }

    private companion object {
        const val MAX_TITLE = 200
        const val MAX_BODY = 1_000
    }
}

/** Where a tapped notification leads. */
sealed interface NotificationTarget {
    val accountId: String

    /** The request waiting for the member's yes. */
    data class Request(override val accountId: String, val approvalId: String) : NotificationTarget

    /** A notification about a session (its agent waits for the member, its turn failed). */
    data class Session(override val accountId: String, val sessionId: String) : NotificationTarget

    /** A notification about a wait outside any session: the account's waits. */
    data class Waits(override val accountId: String) : NotificationTarget

    /** A notification about nothing to open (a test push): the app opens on the account. */
    data class Home(override val accountId: String) : NotificationTarget
}

/**
 * The link a tapped notification opens:
 * `ostrom://notification?origin=<encoded origin>&org=<id>&member=<id>[&approval=<id>][&session=<id>][&wait=<id>]`.
 * It names the server, organization and member, not the account id, because the iOS service extension that builds it
 * does not see the app's accounts. A crafted one only opens a screen of an account already signed in on this device;
 * a request's answer still asks for the person's unlock.
 */
object NotificationLinks {
    const val SCHEME = "ostrom://notification?"
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private const val HEX = "0123456789ABCDEF"

    internal fun isId(value: String): Boolean = ID.matches(value)

    fun link(origin: String, orgId: String, memberId: String, approvalId: String?, sessionId: String? = null, waitId: String? = null): String = buildString {
        append(SCHEME).append("origin=").append(encode(origin))
        append("&org=").append(encode(orgId)).append("&member=").append(encode(memberId))
        approvalId?.let { append("&approval=").append(encode(it)) }
        sessionId?.let { append("&session=").append(encode(it)) }
        waitId?.let { append("&wait=").append(encode(it)) }
    }

    /** The link's origin, organization, member, request, session and wait; null for anything else. */
    internal fun parse(link: String): Parsed? {
        if (!link.startsWith(SCHEME, ignoreCase = true)) return null
        val values = link.substring(SCHEME.length).substringBefore('#').split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "")
            if (key.isEmpty()) null else key to part.substringAfter('=')
        }
        // A key given twice is refused: which one wins would be a guess.
        if (values.map { it.first }.toSet().size != values.size) return null
        fun single(key: String): String? = values.firstOrNull { it.first == key }?.second
        val origin = single("origin")?.let(::decode) ?: return null
        val org = single("org")?.takeIf(::isId) ?: return null
        val member = single("member")?.takeIf(::isId) ?: return null
        val approval = single("approval")?.let { it.takeIf(::isId) ?: return null }
        val session = single("session")?.let { it.takeIf(::isId) ?: return null }
        val wait = single("wait")?.let { it.takeIf(::isId) ?: return null }
        return Parsed(origin, org, member, approval, session, wait)
    }

    internal data class Parsed(
        val origin: String,
        val orgId: String,
        val memberId: String,
        val approvalId: String?,
        val sessionId: String? = null,
        val waitId: String? = null,
    )

    private fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = (byte.toInt() and 0xff).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") {
                append(c)
            } else {
                append('%').append(HEX[byte.toInt() shr 4 and 0xf]).append(HEX[byte.toInt() and 0xf])
            }
        }
    }

    /** Percent-decoding of one query value; null for a malformed escape or bytes that are not UTF-8. */
    internal fun decode(value: String): String? {
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%') {
                if (i + 2 >= value.length) return null
                val hex = value.substring(i + 1, i + 3)
                // Two hexadecimal digits exactly: toIntOrNull alone would take a sign ("%-1").
                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                bytes += hex.toInt(16).toByte()
                i += 3
            } else {
                // The whole unescaped run at once, so a surrogate pair is encoded as one character.
                val end = (i until value.length).firstOrNull { value[it] == '%' } ?: value.length
                value.substring(i, end).encodeToByteArray().forEach { bytes += it }
                i = end
            }
        }
        return try {
            bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            null
        }
    }
}

/**
 * Resolves a tapped notification's link ([NotificationLinks]) to a signed-in account of its server, organization and
 * member, and what it opens: its request, else its session, else the account's waits for a wait outside a session,
 * else the account. Null for another link, or when no such account is signed in on this device: nothing opens.
 */
class ResolveNotificationUseCase(private val accounts: AccountRepository) {
    operator fun invoke(link: String): NotificationTarget? {
        val parsed = NotificationLinks.parse(link) ?: return null
        val account = accounts.accounts.value.accountFor(parsed.origin, parsed.orgId, parsed.memberId) ?: return null
        return when {
            parsed.approvalId != null -> NotificationTarget.Request(account.id, parsed.approvalId)
            parsed.sessionId != null -> NotificationTarget.Session(account.id, parsed.sessionId)
            parsed.waitId != null -> NotificationTarget.Waits(account.id)
            else -> NotificationTarget.Home(account.id)
        }
    }
}

/**
 * The signed-in account of that server (its public URL's origin, normalized as the app's origins are), organization and
 * member. A server reached under another name than its public URL is not recognized: its pushes are not shown.
 */
private fun List<Account>.accountFor(origin: String, orgId: String, memberId: String): Account? {
    val normalized = try {
        // A public URL with a path names the server at its origin.
        normalizeOrigin(origin.substringBefore("://") + "://" + origin.substringAfter("://").substringBefore('/'))
    } catch (_: AppException.InvalidOrigin) {
        return null
    }
    return firstOrNull { it.signedIn && it.origin == normalized && it.organizationId == orgId && it.memberId == memberId }
}
