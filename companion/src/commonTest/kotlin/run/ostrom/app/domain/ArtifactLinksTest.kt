// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion ResolveLinkUseCaseTest (Apache-2.0); see NOTICE.
package run.ostrom.app.domain

import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.LinkRefusal
import run.ostrom.app.domain.usecases.links.ArtifactLinkTarget
import run.ostrom.app.domain.usecases.links.ArtifactLinks
import run.ostrom.app.domain.usecases.links.OpenArtifactLinkUseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArtifactLinksTest {
    private val id = "0b5c7c1e-9f0a-4d2b-8e5f-3a1c2d4e6f80"

    private fun encode(url: String) = url.replace("%", "%25").replace(":", "%3A").replace("/", "%2F").replace("?", "%3F").replace("&", "%26").replace("=", "%3D")

    private fun link(url: String) = "ostrom://open?url=${encode(url)}"

    @Test
    fun theUrlIsReadFromTheLinkDecoded() {
        assertEquals("https://app.example.org/artifacts/$id", ArtifactLinks.urlOf(link("https://app.example.org/artifacts/$id")))
        assertEquals("https://a.example/artifacts/x?v=2", ArtifactLinks.urlOf("OSTROM://open?url=https%3A%2F%2Fa.example%2Fartifacts%2Fx%3Fv%3D2"))
        assertTrue(ArtifactLinks.isOpenLink(" ostrom://open?url=x "))
        assertFalse(ArtifactLinks.isOpenLink("ostrom://notification?origin=x"))
        // A sender that did not encode the page keeps its whole query.
        assertEquals("https://a.example/artifacts/x?utm=y&v=3", ArtifactLinks.urlOf("ostrom://open?url=https://a.example/artifacts/x?utm=y&v=3"))
        assertNull(ArtifactLinks.urlOf("ostrom://open?other=1"), "no url")
        assertNull(ArtifactLinks.urlOf("ostrom://open?other=1&url=x"), "the page is the only parameter")
        assertNull(ArtifactLinks.urlOf("ostrom://open?url=a&url=b"), "two urls")
        assertNull(ArtifactLinks.urlOf("ostrom://open?url=%zz"), "a malformed escape")
        assertNull(ArtifactLinks.urlOf("ostrom://open?url=%C3"), "bytes that are not UTF-8")
        assertNull(ArtifactLinks.urlOf("ostrom://open?url="), "empty")
        assertNull(ArtifactLinks.urlOf("https://app.example.org/artifacts/$id"), "not the app's scheme")
    }

    @Test
    fun aSignedInAccountsServerOrConsoleOpensTheArtifactAndBecomesActive() {
        val list = accounts()
        list.save(account("a1", origin = "https://ostrom.example.org"))
        list.save(account("a2", origin = "https://api.acme.dev"))
        list.setActive("a1")
        val open = OpenArtifactLinkUseCase(list)
        // The console of api.acme.dev is app.acme.dev: its account opens it, and becomes the active one.
        assertEquals(ArtifactLinkTarget("a2", id, null), open(link("https://app.acme.dev/artifacts/$id")))
        assertEquals("a2", list.activeAccountId.value)
        // The server's own origin, a trailing slash, a version, a tracker and a fragment.
        assertEquals(ArtifactLinkTarget("a1", id, 3), open(link("https://OSTROM.example.org:443/artifacts/$id/?utm=x&v=3#top")))
        // Not encoded by its sender: its version is still read.
        assertEquals(ArtifactLinkTarget("a1", id, 4), open("ostrom://open?url=https://ostrom.example.org/artifacts/$id?utm=x&v=4"))
        assertEquals("a1", list.activeAccountId.value)
    }

    @Test
    fun theActiveAccountIsChosenAmongThoseOfTheServerAndASignedOutOneIsRefused() {
        val list = accounts()
        list.save(account("a1", login = "ada"))
        list.save(account("a2", login = "charles"))
        list.setActive("a2")
        assertEquals("a2", OpenArtifactLinkUseCase(list)(link("https://ostrom.example.org/artifacts/$id")).accountId)
        val signedOut = accounts().also { it.save(account("a1", signedIn = false)) }
        assertEquals(LinkRefusal.SIGNED_OUT, assertFailsWith<AppException.LinkRefused> { OpenArtifactLinkUseCase(signedOut)(link("https://ostrom.example.org/artifacts/$id")) }.reason)
    }

    @Test
    fun anythingElseIsRefusedWithItsReason() {
        val list = accounts().also { it.save(account("a1", origin = "https://api.acme.dev")) }
        list.setActive("a1")
        val open = OpenArtifactLinkUseCase(list)
        fun reason(link: String) = assertFailsWith<AppException.LinkRefused> { open(link) }.reason
        assertEquals(LinkRefusal.UNKNOWN_SERVER, reason(link("https://evil.example/artifacts/$id")))
        // A look-alike console: only app. for api. counts.
        assertEquals(LinkRefusal.UNKNOWN_SERVER, reason(link("https://app.acme.dev.evil.example/artifacts/$id")))
        assertEquals(LinkRefusal.UNKNOWN_SERVER, reason(link("https://www.acme.dev/artifacts/$id")))
        listOf(
            "ostrom://open?url=nothing",
            link("https://api.acme.dev/other/$id"),
            link("https://api.acme.dev/artifacts/$id/files/index.html"),
            link("https://api.acme.dev/artifacts/not-an-id-not-an-id-not-an-id-not-an"),
            link("https://api.acme.dev/artifacts/${id.uppercase()}"),
            link("https://user@api.acme.dev/artifacts/$id"),
            link("http://api.acme.dev/artifacts/$id"),
            link("javascript://api.acme.dev/artifacts/$id"),
            link("https://api.acme.dev/artifacts/$id?v=0"),
            link("https://api.acme.dev/artifacts/$id?v=1&v=2"),
            link("https://api.acme.dev/artifacts/$id?v=x"),
            link("https://api.acme.dev/artifacts/$id?v=%2B5"),
            link("https://api.acme.dev/artifacts/$id?v="),
        ).forEach { assertEquals(LinkRefusal.NOT_AN_ARTIFACT, reason(it), it) }
        assertEquals("a1", list.activeAccountId.value, "a refused link changes no account")
    }
}
