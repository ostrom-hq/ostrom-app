// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/usecases/push/PushUseCases.kt and ReadPushUseCase.kt; see NOTICE.
package run.ostrom.app.domain.usecases.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
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
 * member, so a phone signed in to several accounts tells them apart: the account's member id is read for that once it
 * is registered. A failure is left for the next start or change: the server keeps the last token it had.
 */
class SyncPushUseCase(
    private val accounts: AccountRepository,
    private val features: FeaturesRepository,
    private val push: PushRepository,
    private val auth: AuthRepository,
    private val platform: PushPlatform,
    /** Accounts left out: the demo account, which has no server. */
    private val skip: (String) -> Boolean = { false },
) {
    private data class Registration(val accountId: String, val organizationId: String?, val token: String)

    /** Runs until cancelled. */
    suspend operator fun invoke() {
        val registered = mutableSetOf<Registration>()
        combine(platform.token, accounts.accounts) { token, list ->
            token to list.filter { it.signedIn && !skip(it.id) }.map { it.id to it.organizationId }.toSet()
        }
            .distinctUntilChanged()
            .collect { (token, signedIn) ->
                if (token == null) return@collect
                val wanted = signedIn.map { (id, organization) -> Registration(id, organization, token) }.toSet()
                registered.retainAll(wanted)
                for (registration in wanted) {
                    if (registration in registered) continue
                    if (register(registration.accountId, token)) registered += registration
                }
            }
    }

    private suspend fun register(accountId: String, token: String): Boolean = try {
        if (!features.has(accountId, Features.NOTIFICATIONS_PUSH)) {
            false
        } else {
            push.register(accountId, token, platform.publicKey(), platform.platform, pushLocale(platform.locale))
            readMember(accountId)
            true
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    /** The account's member id, which its pushes name, when it was not read yet. */
    private suspend fun readMember(accountId: String) {
        if (accounts.find(accountId)?.memberId != null) return
        val member = try {
            auth.me(accountId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return
        }
        val current = accounts.find(accountId) ?: return
        val memberId = member.memberId ?: return
        // Only for the organization the call acted in: one chosen meanwhile has another membership.
        if (current.signedIn && current.memberId == null && member.organization?.id == current.organizationId) {
            accounts.update(current.copy(memberId = memberId))
        }
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

/** Before a sign-out: the server stops pushing to this phone for the account. Best effort, never blocks the sign-out. */
class ClearPushUseCase(private val push: PushRepository) {
    suspend operator fun invoke(accountId: String) {
        try {
            push.clear(accountId)
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
        val link = NotificationLinks.link(push.origin, push.orgId, push.memberId, push.approvalId?.takeIf(NotificationLinks::isId))
        return PushNotice(push.id, account.id, title, push.body.take(MAX_BODY), link)
    }

    private companion object {
        const val MAX_TITLE = 200
        const val MAX_BODY = 1_000
    }
}

/** Where a tapped notification leads. */
sealed interface NotificationTarget {
    /** The request waiting for the member's yes. */
    data class Request(val accountId: String, val approvalId: String) : NotificationTarget

    /** A notification about nothing to open (a test push, a session): the app opens on the account. */
    data class Home(val accountId: String) : NotificationTarget
}

/**
 * The link a tapped notification opens: `ostrom://notification?origin=<encoded origin>&org=<id>&member=<id>[&approval=<id>]`.
 * It names the server, organization and member, not the account id, because the iOS service extension that builds it
 * does not see the app's accounts. A crafted one only opens a request of an account already signed in on this device,
 * where the answer still asks for the person's unlock.
 */
object NotificationLinks {
    const val SCHEME = "ostrom://notification?"
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
    private const val HEX = "0123456789ABCDEF"

    internal fun isId(value: String): Boolean = ID.matches(value)

    fun link(origin: String, orgId: String, memberId: String, approvalId: String?): String = buildString {
        append(SCHEME).append("origin=").append(encode(origin))
        append("&org=").append(encode(orgId)).append("&member=").append(encode(memberId))
        approvalId?.let { append("&approval=").append(encode(it)) }
    }

    /** The link's origin, organization, member and request; null for anything else. */
    internal fun parse(link: String): Parsed? {
        if (!link.startsWith(SCHEME, ignoreCase = true)) return null
        val values = link.substring(SCHEME.length).substringBefore('#').split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "")
            if (key.isEmpty()) null else key to part.substringAfter('=')
        }
        // A key given twice is refused: which one wins would be a guess.
        fun single(key: String): String? = values.filter { it.first == key }.let { if (it.size > 1) return null else it.singleOrNull()?.second }
        val origin = single("origin")?.let(::decode) ?: return null
        val org = single("org")?.takeIf(::isId) ?: return null
        val member = single("member")?.takeIf(::isId) ?: return null
        val approval = single("approval")?.let { it.takeIf(::isId) ?: return null }
        return Parsed(origin, org, member, approval)
    }

    internal data class Parsed(val origin: String, val orgId: String, val memberId: String, val approvalId: String?)

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
 * member. Null for another link, or when no such account is signed in on this device: nothing opens.
 */
class ResolveNotificationUseCase(private val accounts: AccountRepository) {
    operator fun invoke(link: String): NotificationTarget? {
        val parsed = NotificationLinks.parse(link) ?: return null
        val account = accounts.accounts.value.accountFor(parsed.origin, parsed.orgId, parsed.memberId) ?: return null
        return parsed.approvalId?.let { NotificationTarget.Request(account.id, it) } ?: NotificationTarget.Home(account.id)
    }
}

/** The signed-in account of that server (its public URL, normalized as the app's origins are), organization and member. */
private fun List<Account>.accountFor(origin: String, orgId: String, memberId: String): Account? {
    val normalized = try {
        normalizeOrigin(origin)
    } catch (_: AppException.InvalidOrigin) {
        return null
    }
    return firstOrNull { it.signedIn && it.origin == normalized && it.organizationId == orgId && it.memberId == memberId }
}
