// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../SettingsRepositoriesTest.kt; see NOTICE.
package run.ostrom.app.infrastructure

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.services.IdGenerator
import run.ostrom.app.infrastructure.settings.SettingsInstallationIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsInstallationIdentityTest {
    private val ids = object : IdGenerator {
        private var next = 0
        override fun newId(): String = "00000000-0000-4000-8000-00000000000${next++}"
    }

    @Test
    fun theInstallationIdIsDrawnOnceAndAnInvalidOneReplaced() = runTest {
        val settings = MapSettings(SettingsInstallationIdentity.KEY to "not-a-uuid")
        val id = SettingsInstallationIdentity(settings, ids).id()
        assertEquals("00000000-0000-4000-8000-000000000000", id)
        // Read again by another start of the app: the same id, nothing drawn.
        assertEquals(id, SettingsInstallationIdentity(settings, ids).id())
        assertEquals(id, settings.getStringOrNull(SettingsInstallationIdentity.KEY))
    }
}
