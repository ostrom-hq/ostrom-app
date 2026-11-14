// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../domain/ArtifactUseCasesTest.kt; see NOTICE.
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FixedClock
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Artifact
import run.ostrom.app.domain.models.ArtifactPage
import run.ostrom.app.domain.models.ArtifactTicket
import run.ostrom.app.domain.models.ArtifactVersion
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.repositories.ArtifactRepository
import run.ostrom.app.domain.usecases.artifacts.GetArtifactUseCase
import run.ostrom.app.domain.usecases.artifacts.LoadArtifactsUseCase
import run.ostrom.app.domain.usecases.artifacts.OpenArtifactUseCase
import run.ostrom.app.domain.usecases.artifacts.isWebLink
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactUseCasesTest {
    private val id = "0b5c7c1e-9f0a-4d2b-8e5f-3a1c2d4e6f80"
    private val content = "https://artifacts.example.org"

    private class FakeArtifacts : ArtifactRepository {
        val calls = mutableListOf<String>()
        var url = "https://app.example.org/artifacts/x"
        var tickets: (String, Int?) -> ArtifactTicket = { id, version -> ArtifactTicket(id, version ?: 2, "https://artifacts.example.org/a/$id/tk_1/index.html", null) }

        override suspend fun artifacts(accountId: String, before: String?): ArtifactPage {
            calls += "list ${before.orEmpty()}".trim()
            return ArtifactPage(emptyList(), "c2")
        }

        override suspend fun artifact(accountId: String, artifactId: String): Artifact {
            calls += "get $artifactId"
            return Artifact(artifactId, "Plan", "ada", "team", "owner", 2, url, 1L)
        }

        override suspend fun versions(accountId: String, artifactId: String): List<ArtifactVersion> {
            calls += "versions $artifactId"
            return listOf(ArtifactVersion(2, 2L, "ada", 1), ArtifactVersion(1, 1L, "ada", 1))
        }

        override suspend fun ticket(accountId: String, artifactId: String, version: Int?): ArtifactTicket {
            calls += "ticket $artifactId $version"
            return tickets(artifactId, version)
        }
    }

    private fun guard() = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts(), MemoryCredentialStore(), FixedClock()))

    @Test
    fun theViewerStaysUnderItsTicket() {
        val prefix = "$content/a/$id/tk_1/"
        assertEquals(prefix, OpenArtifactUseCase.contentPrefix(id, prefix))
        assertEquals(prefix, OpenArtifactUseCase.contentPrefix(id, "${prefix}index.html"))
        // An entry page in a folder keeps the ticket's directory as the prefix.
        assertEquals(prefix, OpenArtifactUseCase.contentPrefix(id, "${prefix}docs/index.html"))
        // The server's own spelling of its content origin is accepted, and kept in the canonical form.
        assertEquals(prefix, OpenArtifactUseCase.contentPrefix(id, "https://Artifacts.Example.org:443/a/$id/tk_1/"))
        assertEquals("http://localhost:8081/a/$id/tk_1/", OpenArtifactUseCase.contentPrefix(id, "http://localhost:8081/a/$id/tk_1/index.html"))
        assertTrue(OpenArtifactUseCase.allows(prefix, "https://ARTIFACTS.example.org:443/a/$id/tk_1/page.html"))
        for (url in listOf(
            "$content/a/$id/../x/", "$content/a/other/tk/", "$content/a/$id/tk%2F1/", "https://u@artifacts.example.org/a/$id/tk/", content,
            "$content/l/slug/index.html", "http://artifacts.example.org/a/$id/tk/", "$content/a/$id/../", "$content/a/$id/tk", "ftp://artifacts.example.org/a/$id/tk/",
            "$content/x/a/$id/tk/",
        )) assertNull(OpenArtifactUseCase.contentPrefix(id, url), url)
        assertTrue(OpenArtifactUseCase.allows(prefix, "${prefix}img/a.png?x=1#top"))
        // A percent in a file name is a page; escaped twice, a dot or a slash is not.
        assertTrue(OpenArtifactUseCase.allows(prefix, "${prefix}chart%20100%25.png"))
        assertFalse(OpenArtifactUseCase.allows(prefix, "${prefix}%252f..%252fx"))
        for (url in listOf("${prefix}../other/", "${prefix}%2e%2e/x", "$content/dashboard", "${prefix}a\\..\\b", "${prefix}..%5c..%5cx", "${prefix}%252e%252e/x", "$content/a/$id/tk_2/", "not a url", "https://"))
            assertFalse(OpenArtifactUseCase.allows(prefix, url), url)
        // The demo's page held in memory has no prefix: nothing navigates in it.
        assertFalse(OpenArtifactUseCase.allows("", "$content/a/$id/tk_1/"))
    }

    @Test
    fun aTicketForAnotherArtifactVersionOrShapeIsRefused() = runTest {
        val artifacts = FakeArtifacts()
        val open = OpenArtifactUseCase(artifacts, FakeFeatures(setOf(Features.ARTIFACTS)), guard())
        val opened = open("a1", id, null)
        assertEquals("$content/a/$id/tk_1/", opened.prefix)
        assertNull(opened.html)
        assertEquals(2, open("a1", id, 2).ticket.version)
        artifacts.tickets = { a, _ -> ArtifactTicket(a, 2, "https://evil.example/elsewhere/$a/t/", null) }
        assertEquals("invalid_ticket", assertFailsWith<AppException.Server> { open("a1", id, null) }.code)
        artifacts.tickets = { _, _ -> ArtifactTicket("other", 2, "$content/a/other/t/", null) }
        assertFailsWith<AppException.Server> { open("a1", id, null) }
        artifacts.tickets = { a, _ -> ArtifactTicket(a, 2, "$content/a/$a/t/", null) }
        assertFailsWith<AppException.Server> { open("a1", id, 3) }
        assertFailsWith<AppException.NotFound> { open("a1", "../x", null) }
        assertFailsWith<AppException.Unsupported> { OpenArtifactUseCase(artifacts, FakeFeatures(), guard())("a1", id, null) }
        // The demo's page is shown from memory, with nowhere to navigate.
        artifacts.tickets = { a, v -> ArtifactTicket(a, v ?: 1, "about:blank", null, inlineHtml = "<p>Hi</p>") }
        val inline = open("a1", id, null)
        assertEquals("<p>Hi</p>", inline.html)
        assertEquals("", inline.prefix)
    }

    @Test
    fun theGalleryIsHiddenFromAServerWithoutArtifacts() = runTest {
        val artifacts = FakeArtifacts(); val features = FakeFeatures()
        val load = LoadArtifactsUseCase(artifacts, features, guard())
        assertFailsWith<AppException.Unsupported> { load("a1") }
        assertEquals(emptyList(), artifacts.calls)
        features.features = setOf(Features.ARTIFACTS)
        assertEquals("c2", load("a1").next)
        load("a1", "c2")
        assertEquals(listOf("list", "list c2"), artifacts.calls)
    }

    @Test
    fun anArtifactComesWithItsVersionsAndItsConsolePageOnlyWhenItIsAWebLink() = runTest {
        val artifacts = FakeArtifacts()
        val get = GetArtifactUseCase(artifacts, guard())
        val details = get("a1", id)
        assertEquals(listOf(2, 1), details.versions.map { it.number })
        assertEquals("https://app.example.org/artifacts/x", details.viewerUrl)
        artifacts.url = "javascript:alert(1)"
        assertNull(get("a1", id).viewerUrl)
        artifacts.url = ""
        assertNull(get("a1", id).viewerUrl)
        assertEquals(2, get.current("a1", id).latestVersion)
        assertFailsWith<AppException.NotFound> { get("a1", "not-an-id") }
    }

    @Test
    fun onlyPlainWebLinksOpenOutside() {
        assertTrue(isWebLink("https://example.org/a?b=1"))
        assertTrue(isWebLink("HTTP://example.org"))
        for (link in listOf("javascript:alert(1)", "file:///etc/passwd", "https://", "https://exa mple.org", "https://example.org/‮evil", "mailto:a@b.c", ""))
            assertFalse(isWebLink(link), link)
    }
}
