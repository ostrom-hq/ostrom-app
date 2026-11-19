// SPDX-License-Identifier: Apache-2.0
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
import kotlin.test.assertNull

class ArtifactLinkDecodingTest {
    private val id = "0b5c7c1e-9f0a-4d2b-8e5f-3a1c2d4e6f80"

    @Test
    fun escapesInEitherCaseAndPlusSignsAreDecoded() {
        assertEquals("a b/c?d", ArtifactLinks.decode("a+b%2fc%3Fd"))
        assertEquals("é", ArtifactLinks.decode("%C3%a9"))
        assertEquals("😀 x", ArtifactLinks.decode("😀+x"))
    }

    @Test
    fun aTruncatedOrNonHexadecimalEscapeIsMalformed() {
        assertNull(ArtifactLinks.decode("abc%4"))
        assertNull(ArtifactLinks.decode("abc%"))
        assertNull(ArtifactLinks.decode("%G1"))
        assertNull(ArtifactLinks.decode("%1g"))
        assertNull(ArtifactLinks.decode("%-1"))
    }

    @Test
    fun bytesThatAreNotUtf8AreMalformed() {
        assertNull(ArtifactLinks.decode("%FF%FE"))
    }

    @Test
    fun aLinkCarryingNothingButBlanksOrAMalformedUrlHasNoUrl() {
        assertNull(ArtifactLinks.urlOf("ostrom://open?url=%20+"))
        assertNull(ArtifactLinks.urlOf("ostrom://open?url=%ZZ"))
        assertNull(ArtifactLinks.urlOf("ostrom://open?page=x"))
        assertNull(ArtifactLinks.urlOf("https://ostrom.example.org"))
    }

    @Test
    fun aLinkWithoutAPageOrWithAnOverlongVersionIsNotAnArtifact() {
        val list = accounts().also { it.save(account("a1", origin = "https://api.acme.dev")) }
        val open = OpenArtifactLinkUseCase(list)
        fun reason(link: String) = assertFailsWith<AppException.LinkRefused> { open(link) }.reason
        assertEquals(LinkRefusal.NOT_AN_ARTIFACT, reason("ostrom://open?page=https%3A%2F%2Fapi.acme.dev%2Fartifacts%2F$id"))
        assertEquals(LinkRefusal.NOT_AN_ARTIFACT, reason("ostrom://open?url=https%3A%2F%2Fapi.acme.dev%2Fartifacts%2F$id%3Fv%3D1234567890"))
        assertEquals(
            ArtifactLinkTarget("a1", id, 123456789),
            open("ostrom://open?url=https%3A%2F%2Fapi.acme.dev%2Fartifacts%2F$id%3Fv%3D123456789"),
        )
    }
}
