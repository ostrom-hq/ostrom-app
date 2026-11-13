// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/src/jvmMain/kotlin/me/nathanfallet/teamcode/companion/infrastructure/system/JavaSecureRandom.kt; see NOTICE.
package run.ostrom.app.infrastructure.system

import run.ostrom.app.domain.services.SecureRandom

/** `java.security.SecureRandom`, the platform's default strong source. */
object JavaSecureRandom : SecureRandom {
    private val source = java.security.SecureRandom()
    override fun nextBytes(count: Int): ByteArray = ByteArray(count).also(source::nextBytes)
}
