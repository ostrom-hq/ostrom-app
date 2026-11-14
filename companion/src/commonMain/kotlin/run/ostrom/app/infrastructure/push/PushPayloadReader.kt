// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../infrastructure/push/PushPayloadReader.kt; see NOTICE.
package run.ostrom.app.infrastructure.push

import kotlinx.serialization.json.Json
import run.ostrom.app.domain.models.PushMessage
import run.ostrom.core.api.models.PushPayload

/** The decrypted JSON of a push, read as ostrom-core's `PushPayload`; unknown fields are ignored. */
object PushPayloadReader {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(text: String): PushMessage = json.decodeFromString(PushPayload.serializer(), text).run {
        PushMessage(id, origin, orgId, memberId, kind, title, body, approvalId, sessionId, waitId)
    }
}
