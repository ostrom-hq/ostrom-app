// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.ORIGIN
import run.ostrom.app.account
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.PushMessage
import run.ostrom.app.domain.repositories.Member
import run.ostrom.app.domain.repositories.PushRepository
import run.ostrom.app.domain.services.PushPlatform
import run.ostrom.app.domain.usecases.push.NotificationLinks
import run.ostrom.app.domain.usecases.push.ReadPushUseCase
import run.ostrom.app.domain.usecases.push.ShareSignedInAccountsUseCase
import run.ostrom.app.domain.usecases.push.SyncPushUseCase
import run.ostrom.app.domain.usecases.push.pushLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PushEdgeCasesTest {
    /** A platform that shares nothing (the default) and opens [message] whatever the payload. */
    private class Platform(var message: PushMessage? = null) : PushPlatform {
        override val platform = "android"
        override val locale: String? = null
        override val token = MutableStateFlow<String?>(null)
        override suspend fun publicKey() = "spki"
        override suspend fun open(payload: String): PushMessage = message ?: error("not for this key")
    }

    private class Registrations : PushRepository {
        val registered = mutableListOf<String>()
        override suspend fun register(accountId: String, token: String, publicKey: String, platform: String, locale: String?) { registered += accountId }
        override suspend fun clear(accountId: String) = Unit
    }

    private val accounts = accounts()
    private val platform = Platform()
    private val push = Registrations()

    // --- The phone's language ---

    @Test
    fun onlyAUsableLanguageTagIsSent() {
        assertEquals("ar-EG", pushLocale("ar-EG-u-nu-latn"))
        assertEquals("zh-Hans-CN", pushLocale(" zh_Hans_CN "))
        assertEquals("en", pushLocale("en-x"))
        assertNull(pushLocale(null))
        assertNull(pushLocale("und-FR"))
        assertNull(pushLocale("e"))
        assertNull(pushLocale("engl-US"))
        assertNull(pushLocale("1a-US"))
        // A letter outside ASCII is a letter, but not one the server takes.
        assertNull(pushLocale("fé-FR"))
    }

    // --- Registering, and reading the member a push names ---

    private fun sync(auth: FakeAuthRepository) = SyncPushUseCase(accounts, FakeFeatures(setOf(Features.NOTIFICATIONS_PUSH)), push, auth, platform)

    @Test
    fun anAccountWhoseMemberIsKnownIsRegisteredWithoutAsking() = runTest {
        accounts.save(account("a1", memberId = "m1").copy(organizationId = "o1"))
        val auth = FakeAuthRepository().apply { meError = IllegalStateException("not asked") }
        val job = launch { sync(auth)() }
        platform.token.value = "t1"
        runCurrent()
        assertEquals(listOf("a1"), push.registered)
        job.cancel()
    }

    @Test
    fun aMemberReadForAnotherOrganizationOrWithoutIdIsNotKept() = runTest {
        accounts.save(account("a1").copy(organizationId = "o1"))
        val auth = FakeAuthRepository().apply { member = Member("ada", Organization("o2", "Other"), "m9") }
        val job = launch { sync(auth)() }
        platform.token.value = "t1"
        runCurrent()
        assertNull(accounts.accounts.value.single().memberId)

        // Not kept, so registered again after the retry delay; this time the server names no member id.
        auth.member = Member("ada", Organization("o1", "Acme"), memberId = null)
        advanceTimeBy(60_001)
        runCurrent()
        assertNull(accounts.accounts.value.single().memberId)
        assertEquals(listOf("a1", "a1"), push.registered)
        job.cancel()
    }

    @Test
    fun aMemberReadForAnAccountRemovedOrSignedOutMeanwhileIsNotKept() = runTest {
        accounts.save(account("a1").copy(organizationId = "o1"))
        val auth = object : FakeAuthRepository() {
            override suspend fun me(accountId: String): Member { accounts.remove(accountId); return Member("ada", Organization("o1", "Acme"), "m1") }
        }
        val job = launch { sync(auth)() }
        platform.token.value = "t1"
        runCurrent()
        assertTrue(accounts.accounts.value.isEmpty())
        job.cancel()

        accounts.save(account("a2").copy(organizationId = "o1"))
        val signingOut = object : FakeAuthRepository() {
            override suspend fun me(accountId: String): Member {
                accounts.update(accounts.accounts.value.single().copy(signedIn = false))
                return Member("ada", Organization("o1", "Acme"), "m1")
            }
        }
        val second = launch { sync(signingOut)() }
        runCurrent()
        assertNull(accounts.accounts.value.single().memberId)
        second.cancel()
    }

    @Test
    fun aMemberIdFoundMeanwhileIsNotOverwritten() = runTest {
        accounts.save(account("a1").copy(organizationId = "o1"))
        val auth = object : FakeAuthRepository() {
            override suspend fun me(accountId: String): Member {
                accounts.update(accounts.accounts.value.single().copy(memberId = "m-first"))
                return Member("ada", Organization("o1", "Acme"), "m-second")
            }
        }
        val job = launch { sync(auth)() }
        platform.token.value = "t1"
        runCurrent()
        assertEquals("m-first", accounts.accounts.value.single().memberId)
        job.cancel()
    }

    // --- Sharing the signed-in accounts ---

    @Test
    fun accountsWithoutOrganizationOrWithAMemberALinkWouldRefuseAreLeftOut() = runTest {
        accounts.save(account("a1", memberId = "m1"))
        accounts.save(account("a2", memberId = "m 2").copy(organizationId = "o2"))
        val shared = mutableListOf<Set<String>>()
        val recording = object : PushPlatform by platform {
            override suspend fun signedIn(accounts: Set<String>) { shared += accounts }
        }
        val job = launch { ShareSignedInAccountsUseCase(accounts, recording)() }
        runCurrent()
        assertEquals(listOf(emptySet<String>()), shared)
        job.cancel()
    }

    @Test
    fun aPlatformWithoutAReaderOutsideTheAppSharesNothing() = runTest {
        accounts.save(account("a1", memberId = "m1").copy(organizationId = "o1"))
        val job = launch { ShareSignedInAccountsUseCase(accounts, platform)() }
        runCurrent()
        // The default does nothing and the sharing keeps following the accounts.
        platform.signedIn(setOf("o1 m1"))
        assertTrue(job.isActive)
        job.cancel()
    }

    // --- Reading a push ---

    @Test
    fun anIdALinkWouldRefuseIsLeftOutOfTheNotificationsLink() = runTest {
        accounts.save(account("a1", memberId = "m1").copy(organizationId = "o1"))
        platform.message = PushMessage("n1", ORIGIN, "o1", "m1", "approval", "Deploy", "ada asks", approvalId = "a p", sessionId = "s/1", waitId = "w?")
        val notice = ReadPushUseCase(accounts, platform)("payload")!!
        assertEquals(NotificationLinks.link(ORIGIN, "o1", "m1", approvalId = null), notice.link)
    }

    // --- The notification's link ---

    @Test
    fun everyCharacterOutsideTheUnreservedOnesIsEscapedAndReadBack() {
        val origin = "https://a-b.c_d~e/x y?é"
        val link = NotificationLinks.link(origin, "o1", "m1", approvalId = "ap-1", sessionId = "s_1", waitId = "w1")
        assertTrue("origin=https%3A%2F%2Fa-b.c_d~e%2Fx%20y%3F%C3%A9&" in link, link)
        val parsed = NotificationLinks.parse(link)!!
        assertEquals(NotificationLinks.Parsed(origin, "o1", "m1", "ap-1", "s_1", "w1"), parsed)
    }

    @Test
    fun aMalformedLinkIsRefused() {
        val base = "ostrom://notification?origin=https%3A%2F%2Fx"
        assertEquals(NotificationLinks.Parsed("https://x", "o1", "m1", null), NotificationLinks.parse("$base&org=o1&member=m1&&=ignored#frag"))
        for (link in listOf(
            "ostrom://notification?org=o1&member=m1",
            "ostrom://notification?origin=%E&org=o1&member=m1",
            "ostrom://notification?origin=%GG&org=o1&member=m1",
            "ostrom://notification?origin=%2g&org=o1&member=m1",
            "ostrom://notification?origin=%FF&org=o1&member=m1",
            "$base&org=o%201&member=m1",
            "$base&member=m1",
            "$base&org=o1&member=m%201",
            "$base&org=o1",
            "$base&org=o1&member=m1&approval=a%201",
            "$base&org=o1&member=m1&session=s%201",
            "$base&org=o1&member=m1&wait=w%201",
            "$base&org=o1&member=m1&org=o2",
            "https://ostrom.example.org",
        )) {
            assertNull(NotificationLinks.parse(link), link)
        }
        assertEquals("https://x", NotificationLinks.parse("ostrom://notification?origin=https%3a%2f%2fx&org=o1&member=m1")!!.origin)
    }

    @Test
    fun aSubtagTooLongOrWithASignEndsTheKeptTag() {
        assertEquals("de", pushLocale("de-abcdefghij-AT"))
        assertEquals("fr", pushLocale("fr-a!-FR"))
        assertNull(pushLocale("  "))
    }

    @Test
    fun aMemberReadWithoutOrganizationIsNotKept() = runTest {
        accounts.save(account("a1").copy(organizationId = "o1"))
        val auth = FakeAuthRepository().apply { member = Member("ada", null, "m1") }
        val job = launch { sync(auth)() }
        platform.token.value = "t1"
        runCurrent()
        assertNull(accounts.accounts.value.single().memberId)
        job.cancel()
    }

    @Test
    fun anAccountRemovedWhileItRegistersIsNotWrittenBack() = runTest {
        accounts.save(account("a1").copy(organizationId = "o1"))
        val removing = object : PushRepository by push {
            override suspend fun register(accountId: String, token: String, publicKey: String, platform: String, locale: String?) {
                push.register(accountId, token, publicKey, platform, locale)
                accounts.remove(accountId)
            }
        }
        val auth = FakeAuthRepository().apply { member = Member("ada", Organization("o1", "Acme"), "m1") }
        val job = launch { SyncPushUseCase(accounts, FakeFeatures(setOf(Features.NOTIFICATIONS_PUSH)), removing, auth, platform)() }
        platform.token.value = "t1"
        runCurrent()
        assertEquals(listOf("a1"), push.registered)
        assertTrue(accounts.accounts.value.isEmpty())
        job.cancel()
    }

    @Test
    fun capitalsAndCharactersAroundTheUnreservedRangesAreEscapedExactly() {
        val value = "AZaz09-._~ /:@[`{"
        val link = NotificationLinks.link(value, "o1", "m1", approvalId = null)
        assertTrue("origin=AZaz09-._~%20%2F%3A%40%5B%60%7B&" in link, link)
        assertEquals(value, NotificationLinks.parse(link)!!.origin)
    }

    @Test
    fun anEscapeWithASignOrAPunctuationMarkIsRefused() {
        assertNull(NotificationLinks.parse("ostrom://notification?origin=%-1&org=o1&member=m1"))
        assertNull(NotificationLinks.parse("ostrom://notification?origin=%@1&org=o1&member=m1"))
    }
}
