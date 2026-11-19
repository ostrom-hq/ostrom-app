// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.infrastructure.repositories.RemoteArtifactRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Only a 503 `unavailable` means the server has no page viewer. */
class RemoteArtifactRepositoryEdgesTest {
    private val server = ScriptedServer()

    @Test fun anUnavailableKeyOnAnotherStatusIsAnOrdinaryServerFailure() = runTest {
        server.signedIn()
        server.json("""{"error":{"type":"unavailable","message":"m"}}""", HttpStatusCode.InternalServerError)
        val failure = assertFailsWith<AppException.Server> { RemoteArtifactRepository(server.calls).ticket("a1", "art_1", null) }
        assertEquals(500, failure.statusCode)
        assertEquals("unavailable", failure.code)
    }
}
