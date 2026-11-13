// SPDX-License-Identifier: AGPL-3.0-only
// Ported from TeamCode (Apache-2.0), companion/src/commonMain/kotlin/me/nathanfallet/teamcode/companion/infrastructure/system/SystemServices.kt; see NOTICE.
package run.ostrom.app.infrastructure.system

import kotlin.time.Clock as KotlinClock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import run.ostrom.app.domain.services.Clock
import run.ostrom.app.domain.services.IdGenerator

object SystemClock : Clock {
    override fun nowMillis(): Long = KotlinClock.System.now().toEpochMilliseconds()
}

object RandomIdGenerator : IdGenerator {
    @OptIn(ExperimentalUuidApi::class)
    override fun newId(): String = Uuid.random().toString()
}
