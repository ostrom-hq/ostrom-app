// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSessionProblem
import run.ostrom.app.domain.models.CloudSessionRefusal
import run.ostrom.app.domain.models.GitHubRefusal
import run.ostrom.app.domain.models.LinkRefusal
import run.ostrom.app.domain.models.ProblemRule
import run.ostrom.app.domain.models.TransferProblem
import run.ostrom.app.domain.models.WorkflowEditRefusal
import run.ostrom.app.domain.models.WorkflowProblem
import run.ostrom.app.domain.models.WorkflowRefusal
import run.ostrom.app.presentation.viewmodels.userMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ErrorsTest {
    private fun resourcesOf(errors: List<Throwable>) = errors.map { it.userMessage().resource }

    @Test
    fun `a rate limit with a delay names it, one without keeps the plain sentence`() {
        val with = AppException.RateLimited(30).userMessage()
        assertEquals("Too many attempts. Try again in 30 s.", with.text)
        assertEquals(listOf<Any>(30L), with.args)
        val without = AppException.RateLimited(null).userMessage()
        assertEquals("Too many attempts. Try again later.", without.text)
        assertNotNull(without.resource)
        assertTrue(without.args.isEmpty())
        assertTrue(with.resource != without.resource)
    }

    @Test
    fun `an illegal argument shows its own message, or asks to check the input`() {
        assertEquals("Bad value", IllegalArgumentException("Bad value").userMessage().text)
        assertNull(IllegalArgumentException("Bad value").userMessage().resource)
        val bare = IllegalArgumentException().userMessage()
        assertEquals("Check what you entered.", bare.text)
        assertNotNull(bare.resource)
    }

    @Test
    fun `anything else is the generic sentence`() {
        val failure = IllegalStateException("secret detail").userMessage()
        assertEquals("Something went wrong.", failure.text)
        assertNotNull(failure.resource)
    }

    @Test
    fun `every simple domain failure has its own sentence`() {
        val errors = listOf(
            AppException.InvalidOrigin(), AppException.Unreachable(), AppException.NotOstrom(), AppException.NoGitHubSignIn(),
            AppException.UnsupportedApi(2), AppException.Unauthorized(), AppException.SignInMismatch(), AppException.SignInDenied(),
            AppException.SignUpClosed(), AppException.SignInExpired(), AppException.UntrustedSignInPage(), AppException.OrganizationRequired(),
            AppException.NotFound(), AppException.Unsupported("x"), AppException.StepUpRequired(true), AppException.CredentialStorage(),
            AppException.InstallationUnreadable(), AppException.InstallationBusy(), AppException.RateLimited(null),
        )
        val resources = resourcesOf(errors)
        assertTrue(resources.all { it != null })
        assertEquals(resources.size, resources.toSet().size)
        errors.forEach { assertEquals(it.message, it.userMessage().text) }
    }

    @Test
    fun `workflow refusals map to one sentence each`() {
        val resources = resourcesOf(WorkflowRefusal.entries.map { AppException.WorkflowRefused(it) })
        assertEquals(WorkflowRefusal.entries.size, resources.toSet().size)
        val edits = resourcesOf(WorkflowEditRefusal.entries.map { AppException.WorkflowEditRefused(it) })
        assertEquals(WorkflowEditRefusal.entries.size, edits.toSet().size)
        val missing = AppException.WorkflowInputMissing("Repo").userMessage()
        assertEquals(listOf<Any>("Repo"), missing.args)
        val invalid = AppException.WorkflowInvalid(listOf(WorkflowProblem(ProblemRule.NAME), WorkflowProblem(ProblemRule.NODE_ID))).userMessage()
        assertEquals(listOf<Any>(2), invalid.args)
    }

    @Test
    fun `cloud, transfer and github refusals map to one sentence each`() {
        val cloud = resourcesOf(CloudSessionRefusal.entries.map { AppException.CloudSessionRefused(it) })
        assertEquals(CloudSessionRefusal.entries.size, cloud.toSet().size)
        val invalid = resourcesOf(CloudSessionProblem.entries.map { AppException.CloudSessionInvalid(it) })
        assertEquals(CloudSessionProblem.entries.size, invalid.toSet().size)
        val transfer = resourcesOf(TransferProblem.entries.map { AppException.TransferInvalid(it) })
        assertEquals(TransferProblem.entries.size, transfer.toSet().size)
        val github = resourcesOf(GitHubRefusal.entries.map { AppException.GitHubRefused(it) })
        assertEquals(GitHubRefusal.entries.size, github.toSet().size)
        val link = resourcesOf(LinkRefusal.entries.map { AppException.LinkRefused(it) })
        assertEquals(LinkRefusal.entries.size, link.toSet().size)
    }

    @Test
    fun `a stable code is shown, anything else is not`() {
        assertEquals(listOf<Any>("file_too_large"), AppException.FileRefused("file_too_large", 413).userMessage().args)
        assertTrue(AppException.FileRefused("<script>", 413).userMessage().args.isEmpty())
        assertTrue(AppException.FileRefused(null, 0).userMessage().args.isEmpty())
        assertEquals(listOf<Any>("transfer_pending"), AppException.TransferRefused("transfer_pending").userMessage().args)
        assertTrue(AppException.TransferRefused("Not A Code").userMessage().args.isEmpty())
        assertEquals(listOf<Any>(409, "conflict"), AppException.Server(409, "conflict").userMessage().args)
        assertEquals(listOf<Any>(500), AppException.Server(500, "A message").userMessage().args)
    }

    @Test
    fun `device refusals are worded by the app, an unknown code is shown only when it is a key`() {
        val known = AppException.DeviceRefused.CODES - setOf("folder_not_configured", "folder_missing", "folder_moved", "folder_refused")
        val resources = resourcesOf(known.map { AppException.DeviceRefused(it) })
        assertEquals(known.size, resources.toSet().size)
        listOf("folder_not_configured", "folder_missing", "folder_moved", "folder_refused").forEach {
            assertEquals(listOf<Any>(it), AppException.DeviceRefused(it).userMessage().args)
        }
        assertEquals(listOf<Any>("odd_code"), AppException.DeviceRefused("odd_code").userMessage().args)
        assertTrue(AppException.DeviceRefused("Odd Code").userMessage().args.isEmpty())
    }
}
