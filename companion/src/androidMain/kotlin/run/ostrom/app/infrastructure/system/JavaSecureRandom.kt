// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/androidMain/kotlin/me/nathanfallet/teamcode/companion/infrastructure/system/JavaSecureRandom.kt; see NOTICE.
package run.ostrom.app.infrastructure.system

import run.ostrom.app.domain.services.SecureRandom

/** `java.security.SecureRandom`, the platform's default strong source. */
object JavaSecureRandom : SecureRandom {
    private val source = java.security.SecureRandom()
    override fun nextBytes(count: Int): ByteArray = ByteArray(count).also(source::nextBytes)
}
