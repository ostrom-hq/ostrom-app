// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.models.DeviceFolders
import run.ostrom.app.infrastructure.repositories.RemoteDeviceRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/** A device whose daemon lists no conversations: offline, silent about its features, or without the resume one. */
class RemoteDeviceRepositoryEdgesTest {
    private val server = ScriptedServer()

    @Test fun onlyTheResumeFeatureOfAnOnlineDaemonLetsItsConversationsBeListed() = runTest {
        server.signedIn()
        server.json("""{"deviceId":"d1","daemon":{"online":false,"lastSeenAt":"2026-11-14T09:00:00Z"},"folders":[]}""")
        server.json("""{"deviceId":"d2","daemon":{"online":true,"lastSeenAt":"2026-11-14T09:00:00Z","features":["other.v1"]},"folders":[]}""")
        val repository = RemoteDeviceRepository(server.calls)
        assertEquals(DeviceFolders(false, emptyList(), resumes = false), repository.folders("a1", "d1"))
        assertEquals(DeviceFolders(true, emptyList(), resumes = false), repository.folders("a1", "d2"))
    }
}
