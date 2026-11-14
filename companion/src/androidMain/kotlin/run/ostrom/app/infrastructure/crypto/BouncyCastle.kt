// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure.crypto

import java.security.Security
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Gives the app Ed25519 on every Android version: the platform's providers have it only from API 33, and the receipts
 * of the history are verified on the phone (ostrom-core's receipts module, through the JDK provider). Android ships a
 * stripped provider under the same name "BC": it is replaced by the full one, first in the list. Called once, at start,
 * before anything verifies a receipt.
 */
fun installBouncyCastle() {
    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
    Security.insertProviderAt(BouncyCastleProvider(), 1)
}
