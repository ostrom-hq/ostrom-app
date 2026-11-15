// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../domain/models/SessionActions.kt; see NOTICE.
package run.ostrom.app.domain.models

/** The server's acknowledgement of a message, an interrupt, a permission answer or a task stop. */
data class InputReceipt(
    val inputId: String,
    /** `queued`, `delivered`, `consumed`, `cancelled` or `rejected`. */
    val status: String,
    /** Accepting it resumed a paused session. */
    val woke: Boolean,
    val reason: String?,
)

/**
 * A message the member sent, shown at once and kept until the transcript records it (its `message.user` event carries
 * [inputId]) or the server refuses it. [clientMessageId] stays the same across retries: the server answers a retry
 * with the first acknowledgement, so a message is never sent twice.
 */
data class PendingMessage(
    val clientMessageId: String,
    val text: String,
    val state: State,
    val inputId: String? = null,
    val woke: Boolean = false,
    /** Why it failed or was refused: a stable code, or the domain's sentence for a network failure. */
    val reason: String? = null,
    /** The files it carries (`runners.cloud.files.v1`), already uploaded; sent again as they are on a retry. */
    val files: List<UploadedFile> = emptyList(),
) {
    enum class State { SENDING, SENT, FAILED, REFUSED }
}
