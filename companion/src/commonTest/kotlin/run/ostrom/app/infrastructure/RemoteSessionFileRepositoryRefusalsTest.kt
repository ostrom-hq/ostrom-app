// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.PickedFile
import run.ostrom.app.infrastructure.repositories.RemoteSessionFileRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Which failures of an upload are the file's own refusals and which are what any call translates. */
class RemoteSessionFileRepositoryRefusalsTest {
    private val server = ScriptedServer()
    private val files = RemoteSessionFileRepository(server.calls)
    private fun picked() = PickedFile("a.png", 4) { Buffer().apply { write(byteArrayOf(1, 2, 3, 4)) } }
    private fun refusal(type: String) = """{"error":{"type":"$type","message":"No."}}"""

    private suspend fun upload() = files.upload("a1", "s1", picked(), "k") { _, _ -> }

    @Test fun aSignedOutAnswerAndAConnectionThatDropsAreNotTheFilesFault() = runTest {
        server.signedIn()
        server.json(refusal("unauthorized"), HttpStatusCode.Unauthorized)
        assertFailsWith<AppException.Unauthorized> { upload() }
        repeat(20) { server.fail(IOException("connection reset")) }
        assertFailsWith<AppException.Unreachable> { upload() }
    }

    @Test fun aFreshProofIsAskedForAsForAnyCall() = runTest {
        server.signedIn()
        server.json(refusal("step_up_required"), HttpStatusCode.Forbidden)
        assertFailsWith<AppException.StepUpRequired> { upload() }
    }

    @Test fun aConflictOrAForbiddenAnswerOfAnotherKindIsTheFilesRefusal() = runTest {
        server.signedIn()
        server.json(refusal("session_busy"), HttpStatusCode.Conflict)
        val busy = assertFailsWith<AppException.FileRefused> { upload() }
        assertEquals("session_busy" to 409, busy.code to busy.statusCode)
        server.json(refusal("forbidden"), HttpStatusCode.Forbidden)
        val forbidden = assertFailsWith<AppException.FileRefused> { upload() }
        assertEquals("forbidden" to 403, forbidden.code to forbidden.statusCode)
    }
}
