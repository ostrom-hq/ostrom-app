// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.GitHubRefusal
import run.ostrom.app.infrastructure.repositories.RemoteGitHubLinkRepository
import run.ostrom.app.infrastructure.repositories.refusalOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A poll's failures that do not end the attempt, and the statuses a refusal key stands for. */
class RemoteGitHubLinkRepositoryEdgesTest {
    private val server = ScriptedServer()
    private val repository = RemoteGitHubLinkRepository(server.calls)
    private fun failure(type: String, status: HttpStatusCode) = server.json("""{"error":{"type":"$type","message":"m"}}""", status)

    @Test fun aPollFailsAsAnyCallUnlessTheAttemptIsGoneOrDenied() = runTest {
        server.signedIn()
        failure("something_else", HttpStatusCode.NotFound)
        assertEquals("something_else", assertFailsWith<AppException.Server> { repository.poll("a1", "d1") }.code)
        failure("github_link_denied", HttpStatusCode.NotFound)
        assertEquals("github_link_denied", assertFailsWith<AppException.Server> { repository.poll("a1", "d1") }.code)
        failure("something_else", HttpStatusCode.BadRequest)
        assertEquals(400, assertFailsWith<AppException.Server> { repository.poll("a1", "d1") }.statusCode)
        failure("github_link_not_found", HttpStatusCode.BadRequest)
        assertEquals(400, assertFailsWith<AppException.Server> { repository.poll("a1", "d1") }.statusCode)
        failure("github_link_denied", HttpStatusCode.InternalServerError)
        assertEquals(500, assertFailsWith<AppException.Server> { repository.poll("a1", "d1") }.statusCode)
    }

    @Test fun aRefusalKeyIsOnlyARefusalForAFailureStatus() {
        assertEquals(GitHubRefusal.UNREACHABLE, refusalOf(400, "github_unreachable"))
        assertEquals(GitHubRefusal.UNREACHABLE, refusalOf(599, "github_unreachable"))
        assertNull(refusalOf(399, "github_unreachable"))
        assertNull(refusalOf(600, "github_unreachable"))
        assertNull(refusalOf(0, "github_unreachable"))
        assertNull(refusalOf(409, "something_else"))
        assertNull(refusalOf(409, null))
    }
}
