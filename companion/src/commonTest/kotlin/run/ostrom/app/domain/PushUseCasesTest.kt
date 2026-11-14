// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion PushUseCasesTest.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.domain

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
import run.ostrom.app.domain.models.PushNotice
import run.ostrom.app.domain.repositories.Member
import run.ostrom.app.domain.repositories.PushRepository
import run.ostrom.app.domain.repositories.find
import run.ostrom.app.domain.services.PushPlatform
import run.ostrom.app.domain.usecases.auth.OrganizationUseCases
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.FixedClock
import run.ostrom.app.domain.usecases.push.ClearPushUseCase
import run.ostrom.app.domain.usecases.push.NotificationLinks
import run.ostrom.app.domain.usecases.push.NotificationTarget
import run.ostrom.app.domain.usecases.push.ReadPushUseCase
import run.ostrom.app.domain.usecases.push.ResolveNotificationUseCase
import run.ostrom.app.domain.usecases.push.SyncPushUseCase
import run.ostrom.app.domain.usecases.push.pushLocale
import run.ostrom.app.infrastructure.push.PushPayloadReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The sync runs in backgroundScope, which advanceUntilIdle leaves alone: runCurrent runs it.
class PushUseCasesTest {
    private class FakePushPlatform : PushPlatform {
        override val platform = "android"
        override var locale: String? = "fr-FR"
        override val token = MutableStateFlow<String?>(null)
        override suspend fun publicKey() = "spki"

        // The "encryption" of these tests: a payload is its JSON reversed.
        override suspend fun open(payload: String) =
            if (payload.startsWith("}")) PushPayloadReader.read(payload.reversed()) else error("not for this key")
    }

    private data class Registered(val accountId: String, val token: String, val publicKey: String, val platform: String, val locale: String?)

    private class FakePushRepository : PushRepository {
        val registered = mutableListOf<Registered>()
        val cleared = mutableListOf<String>()
        var failing = false

        override suspend fun register(accountId: String, token: String, publicKey: String, platform: String, locale: String?) {
            if (failing) error("offline")
            registered += Registered(accountId, token, publicKey, platform, locale)
        }

        override suspend fun clear(accountId: String) {
            if (failing) error("offline")
            cleared += accountId
        }
    }

    private val platform = FakePushPlatform()
    private val push = FakePushRepository()
    private val features = FakeFeatures(setOf(Features.NOTIFICATIONS_PUSH))
    private val auth = FakeAuthRepository().apply { member = Member("ada", Organization("o1", "Acme"), "m1") }
    private val accounts = accounts()

    private fun sync(skip: (String) -> Boolean = { false }) = SyncPushUseCase(accounts, features, push, auth, platform, skip)

    @Test
    fun everySignedInAccountIsRegisteredOnceTheTokenArrives() = runTest {
        accounts.save(account("a1").copy(organizationId = "o1"))
        accounts.save(account("a2", signedIn = false))
        backgroundScope.launch { sync()() }
        runCurrent()
        assertEquals(emptyList(), push.registered)

        platform.token.value = "t1"
        runCurrent()
        assertEquals(listOf(Registered("a1", "t1", "spki", "android", "fr-FR")), push.registered)
        // Its member id is read once registered: the pushes name it.
        assertEquals("m1", accounts.find("a1")?.memberId)

        // A new sign-in registers that account alone.
        accounts.save(account("a3"))
        runCurrent()
        assertEquals(listOf("a1", "a3"), push.registered.map { it.accountId })

        // A rotated token goes to every signed-in account again.
        platform.token.value = "t2"
        runCurrent()
        assertEquals(setOf("a1", "a3"), push.registered.filter { it.token == "t2" }.map { it.accountId }.toSet())
        assertEquals(4, push.registered.size)
    }

    @Test
    fun anotherOrganizationRegistersThePhoneThereAndReadsItsMemberAgain() = runTest {
        accounts.save(account("a1").copy(organizationId = "o1", memberId = "m1"))
        backgroundScope.launch { sync()() }
        platform.token.value = "t1"
        runCurrent()
        assertEquals(1, push.registered.size)

        auth.member = Member("ada", Organization("o2", "Beta"), "m2")
        OrganizationUseCases(auth, accounts, AccountGuard(RefreshAccountUseCase(auth, accounts, MemoryCredentialStore(), FixedClock()))).choose("a1", Organization("o2", "Beta"))
        runCurrent()
        assertEquals(2, push.registered.size)
        assertEquals("m2", accounts.find("a1")?.memberId)
    }

    @Test
    fun theMemberIsNotKeptForAnOrganizationTheCallDidNotActIn() = runTest {
        accounts.save(account("a1").copy(organizationId = "o1"))
        auth.member = Member("ada", Organization("o2", "Beta"), "m2")
        backgroundScope.launch { sync()() }
        platform.token.value = "t1"
        runCurrent()
        assertEquals(1, push.registered.size)
        assertNull(accounts.find("a1")?.memberId)
    }

    @Test
    fun aServerWithoutPushIsNotCalledAndAFailureIsRetriedOnTheNextChange() = runTest {
        accounts.save(account("a1"))
        features.features = emptySet()
        backgroundScope.launch { sync()() }
        platform.token.value = "t1"
        runCurrent()
        assertEquals(emptyList(), push.registered)

        features.features = setOf(Features.NOTIFICATIONS_PUSH)
        push.failing = true
        accounts.save(account("a2"))
        runCurrent()
        assertEquals(emptyList(), push.registered)

        push.failing = false
        auth.meError = IllegalStateException("offline")
        accounts.save(account("a3"))
        runCurrent()
        // Registered even when the member could not be read.
        assertEquals(setOf("a1", "a2", "a3"), push.registered.map { it.accountId }.toSet())
    }

    @Test
    fun aFailedRegistrationIsTriedAgainLaterUntilItsMemberIsKnown() = runTest {
        accounts.save(account("a1").copy(organizationId = "o1"))
        push.failing = true
        backgroundScope.launch { sync()() }
        platform.token.value = "t1"
        runCurrent()
        assertEquals(emptyList(), push.registered)

        // Back online: the next try registers, but the member cannot be read yet.
        push.failing = false
        auth.meError = IllegalStateException("offline")
        advanceTimeBy(60_001)
        assertEquals(1, push.registered.size)
        assertNull(accounts.find("a1")?.memberId)

        // The one after waits twice as long, and counts once the member is read.
        auth.meError = null
        advanceTimeBy(60_001)
        assertEquals(1, push.registered.size)
        advanceTimeBy(60_001)
        assertEquals(2, push.registered.size)
        assertEquals("m1", accounts.find("a1")?.memberId)
        advanceTimeBy(10 * 3_600_000L)
        assertEquals(2, push.registered.size)
    }

    @Test
    fun aSlowServerDelaysNoOtherAccount() = runTest {
        accounts.save(account("a1").copy(organizationId = "o1"))
        accounts.save(account("a2").copy(organizationId = "o1"))
        val slow = object : PushRepository by push {
            override suspend fun register(accountId: String, token: String, publicKey: String, platform: String, locale: String?) {
                if (accountId == "a1") kotlinx.coroutines.awaitCancellation()
                push.register(accountId, token, publicKey, platform, locale)
            }
        }
        backgroundScope.launch { SyncPushUseCase(accounts, features, slow, auth, platform)() }
        platform.token.value = "t1"
        runCurrent()
        assertEquals(listOf("a2"), push.registered.map { it.accountId })
    }

    @Test
    fun clearingGivesUpOnAServerThatDoesNotAnswer() = runTest {
        val hung = object : PushRepository by push {
            override suspend fun clear(accountId: String) = kotlinx.coroutines.awaitCancellation()
        }
        ClearPushUseCase(hung, timeoutMillis = 5_000)("a1")
        assertEquals(5_000, testScheduler.currentTime)
    }

    @Test
    fun theDemoAccountIsLeftOut() = runTest {
        accounts.save(account("demo"))
        backgroundScope.launch { sync(skip = { it == "demo" })() }
        platform.token.value = "t1"
        runCurrent()
        assertEquals(emptyList(), push.registered)
    }

    @Test
    fun theLocaleIsTheOneTheServerTakes() {
        assertEquals("fr-FR", pushLocale("fr_FR"))
        assertEquals("zh-Hans-CN", pushLocale("zh-Hans-CN"))
        assertEquals("ar-EG", pushLocale("ar-EG-u-nu-latn"))
        assertEquals("en", pushLocale("en"))
        for (tag in listOf(null, "", "und-x", "1a", "français")) assertNull(pushLocale(tag), tag)
    }

    @Test
    fun clearingIsBestEffort() = runTest {
        val clear = ClearPushUseCase(push)
        clear("a1")
        assertEquals(listOf("a1"), push.cleared)
        push.failing = true
        clear("a2") // No exception: the sign-out goes on.
        assertEquals(listOf("a1"), push.cleared)
    }

    private fun payload(
        origin: String = ORIGIN,
        org: String = "o1",
        member: String = "m1",
        approval: String? = "ap1",
        title: String = "Deploy to production",
    ) = buildString {
        append("""{"v":1,"id":"n1","origin":"$origin","orgId":"$org","memberId":"$member","kind":"approval","title":"$title","body":"ada asks for your yes",""")
        approval?.let { append(""""approvalId":"$it",""") }
        append(""""at":1,"future":true}""")
    }.reversed()

    @Test
    fun aPushIsShownForTheSignedInAccountOfItsServerOrganizationAndMember() = runTest {
        accounts.save(account("a1", login = "ada").copy(organizationId = "o1", memberId = "m0"))
        accounts.save(account("a2", login = "grace").copy(organizationId = "o1", memberId = "m1"))
        val read = ReadPushUseCase(accounts, platform)
        // The server's public URL with a trailing slash names the same server.
        val notice = read(payload(origin = "$ORIGIN/"))
        assertEquals(PushNotice("n1", "a2", "Deploy to production", "ada asks for your yes", NotificationLinks.link("$ORIGIN/", "o1", "m1", "ap1")), notice)

        assertEquals("a2", read(payload(origin = "$ORIGIN/console"))?.accountId, "a public URL with a path")
        assertNull(read(payload(org = "o2")), "another organization")
        assertNull(read(payload(member = "m9")), "another member")
        assertNull(read(payload(origin = "https://other.example")), "another server")
        assertNull(read(payload(origin = "not a url")), "not a server")
        assertNull(read(payload(title = " ")), "nothing to show")
        assertNull(read("not for this key"))
        accounts.update(accounts.find("a2")!!.copy(signedIn = false))
        assertNull(read(payload()), "signed out on this phone")
    }

    @Test
    fun aTappedNotificationOpensItsRequestOrItsAccount() {
        accounts.save(account("a1").copy(organizationId = "o1", memberId = "m1"))
        val resolve = ResolveNotificationUseCase(accounts)
        assertEquals(NotificationTarget.Request("a1", "ap1"), resolve(NotificationLinks.link(ORIGIN, "o1", "m1", "ap1")))
        assertEquals(NotificationTarget.Home("a1"), resolve(NotificationLinks.link(ORIGIN, "o1", "m1", null)))
        assertNull(resolve(NotificationLinks.link(ORIGIN, "o1", "m2", "ap1")))
        assertNull(resolve("https://ostrom.example.org/approvals/ap1"))
    }

    @Test
    fun aCraftedLinkIsRefused() {
        accounts.save(account("a1").copy(organizationId = "o1", memberId = "m1"))
        val resolve = ResolveNotificationUseCase(accounts)
        val origin = "https%3A%2F%2Fostrom.example.org"
        for (link in listOf(
            "ostrom://notification?origin=$origin&org=o1&member=m1&member=m2",
            "ostrom://notification?origin=$origin&org=o1&member=m1&approval=../x",
            "ostrom://notification?origin=$origin&org=o1",
            "ostrom://notification?origin=%ZZ&org=o1&member=m1",
            "ostrom://notification?origin=%E9&org=o1&member=m1",
            "ostrom://notification?origin=https%3A&org=o1&member=m1",
            "ostrom://notification?origin=%2&org=o1&member=m1",
        )) {
            assertNull(resolve(link), link)
        }
        assertEquals(NotificationTarget.Request("a1", "ap1"), resolve("OSTROM://notification?origin=$origin&org=o1&member=m1&approval=ap1#x"))
    }
}
