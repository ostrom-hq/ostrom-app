// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/models/LiveUpdate.kt; see NOTICE.
package run.ostrom.app.domain.models

/**
 * What a followed stream reports. The stream reconnects by itself after a network failure, resuming after the last
 * item it delivered, so items are never lost or repeated; it ends with an [AppException].
 */
sealed interface LiveUpdate<out T> {
    /** The server accepted the stream. [resumed] is false when it starts from "now" without a cursor (the first connection). */
    data class Opened(val resumed: Boolean) : LiveUpdate<Nothing>
    data class Item<T>(val value: T) : LiveUpdate<T>
    /** Events after the cursor were purged: what is shown must be loaded again. */
    data object Gap : LiveUpdate<Nothing>
    /** The connection dropped; the next attempt comes after [delayMillis]. */
    data class Reconnecting(val delayMillis: Long) : LiveUpdate<Nothing>
}
