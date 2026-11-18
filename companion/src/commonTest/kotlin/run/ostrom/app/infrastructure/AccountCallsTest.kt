// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.infrastructure

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import run.ostrom.app.account
import run.ostrom.app.domain.models.AppException
import run.ostrom.core.client.OstromException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a failed call becomes for the screens: a domain failure, never the server's own words. */
class AccountCallsTest {
    private val server = ScriptedServer()

    private suspend fun failure(resource: Boolean = false, error: Exception): Exception {
        server.signedIn()
        return assertFailsWith<Exception> { server.calls.withAccount("a1", resource) { throw error } }
    }

    @Test fun anUnknownAccountIsSignedOutForEveryEntryPoint() = runTest {
        assertFailsWith<AppException.Unauthorized> { server.calls.withAccount("ghost") { } }
        assertFailsWith<AppException.Unauthorized> { server.calls.withAccountIn("ghost", "o1") { } }
        assertFailsWith<AppException.Unauthorized> { server.calls.organizationOf("ghost") }
        assertFailsWith<AppException.Unauthorized> { server.calls.client("ghost") }
    }

    @Test fun theOrganizationOfAnAccountIsItsCurrentChoice() = runTest {
        server.accounts.save(account("a1").copy(organizationId = "org-7"))
        assertEquals("org-7", server.calls.organizationOf("a1"))
        server.accounts.save(account("a2", login = "bob"))
        assertNull(server.calls.organizationOf("a2"))
    }

    @Test fun aCallInAnOrganizationActsThereAndReturnsItsResult() = runTest {
        server.signedIn()
        server.json("""{"items":[]}""")
        val organizations = server.calls.withAccountIn("a1", "org-x") { it.organizations.mine() }
        assertTrue(organizations.isEmpty())
        assertEquals("org-x", server.requests.single().headers["Ostrom-Org"])
    }

    @Test fun aFailureInAnOrganizationCallIsTranslatedToo() = runTest {
        server.signedIn()
        server.json("{}", HttpStatusCode.TooManyRequests)
        assertFailsWith<AppException.RateLimited> { server.calls.withAccountIn("a1", "org-x") { it.organizations.mine() } }
    }

    @Test fun theServersRefusalsAreTranslatedByTheirStatusAndKey() = runTest {
        assertIs<AppException.Unauthorized>(failure(error = OstromException(401, null, "x")))
        assertIs<AppException.OrganizationRequired>(failure(error = OstromException(409, "org_required", "x")))
        val stepUp = assertIs<AppException.StepUpRequired>(failure(error = OstromException(403, "step_up_required", "x", requestBound = true)))
        assertTrue(stepUp.requestBound)
        val limited = assertIs<AppException.RateLimited>(failure(error = OstromException(429, null, "x", retryAfterSeconds = 12)))
        assertEquals(12L, limited.retryAfterSeconds)
        val other = assertIs<AppException.Server>(failure(error = OstromException(409, "conflict", "secret server words")))
        assertEquals(409, other.statusCode)
        assertEquals("conflict", other.code)
    }

    @Test fun aNotFoundMeansGoneOnlyForAResourceAndAForbiddenKeepsItsCode() = runTest {
        assertIs<AppException.NotFound>(failure(resource = true, error = OstromException(404, null, "x")))
        assertEquals(404, assertIs<AppException.Server>(failure(error = OstromException(404, null, "x"))).statusCode)
        assertEquals(403, assertIs<AppException.Server>(failure(resource = true, error = OstromException(403, "forbidden", "x"))).statusCode)
        assertEquals(409, assertIs<AppException.Server>(failure(error = OstromException(409, "not_org_required", "x"))).statusCode)
        assertEquals(403, assertIs<AppException.Server>(failure(error = OstromException(403, "other", "x"))).statusCode)
    }

    @Test fun anAppExceptionPassesThroughUnchanged() = runTest {
        val original = AppException.NotOstrom()
        assertEquals(original.message, assertIs<AppException.NotOstrom>(failure(error = original)).message)
    }

    @Test fun aBodyThatIsNotOstromsIsNotOstromWhetherJsonOrAPortalPage() = runTest {
        assertIs<AppException.NotOstrom>(failure(error = SerializationException("bad json")))
        assertIs<AppException.NotOstrom>(failure(error = RuntimeException("wrapped", SerializationException("deep"))))
    }

    @Test fun anArgumentErrorIsShownAsIsButAnEnginesSubclassIsAnUnreachableServer() = runTest {
        val argument = IllegalArgumentException("bad origin")
        assertEquals("bad origin", assertIs<IllegalArgumentException>(failure(error = argument)).message)
        assertIs<AppException.Unreachable>(failure(error = NumberFormatException("unresolved")))
        assertIs<AppException.Unreachable>(failure(error = RuntimeException("connection refused")))
    }

    @Test fun aCancellationIsRethrownNotTranslated() = runTest {
        server.signedIn()
        assertFailsWith<CancellationException> { server.calls.withAccount("a1") { throw CancellationException("stop") } }
    }

    @Test fun aStalledCallEndsAsUnreachableInsteadOfASpinner() = runTest {
        server.signedIn()
        assertFailsWith<AppException.Unreachable> { server.calls.withAccountLong("a1", timeoutMillis = 50) { delay(60_000) } }
    }

    @Test fun aLongCallReturnsItsResultAndTreatsANotFoundAsAGoneResource() = runTest {
        server.signedIn()
        assertEquals("done", server.calls.withAccountLong("a1", 5_000) { "done" })
        assertFailsWith<AppException.NotFound> { server.calls.withAccountLong("a1", 5_000) { throw OstromException(404, null, "x") } }
    }
}
