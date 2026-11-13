// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/src/iosMain/kotlin/me/nathanfallet/teamcode/companion/infrastructure/system/IosSecureRandom.kt; see NOTICE.
package run.ostrom.app.infrastructure.system

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import run.ostrom.app.domain.services.SecureRandom
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/** `SecRandomCopyBytes`, the system's cryptographically secure generator. */
@OptIn(ExperimentalForeignApi::class)
object IosSecureRandom : SecureRandom {
    override fun nextBytes(count: Int): ByteArray {
        require(count > 0)
        val bytes = ByteArray(count)
        val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) }
        check(status == errSecSuccess) { "This device could not generate a secure random value." }
        return bytes
    }
}
