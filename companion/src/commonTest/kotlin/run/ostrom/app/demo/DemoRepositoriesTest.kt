// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.demo

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.app.FixedClock
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.ApprovalAnswer
import run.ostrom.app.domain.models.ApprovalStatus
import run.ostrom.app.domain.models.NotificationKind
import run.ostrom.app.domain.models.NotificationPreferences
import run.ostrom.app.domain.models.PushTestOutcome
import run.ostrom.app.domain.models.SpendPeriod
import run.ostrom.app.domain.models.WaitResolver
import run.ostrom.app.domain.models.WaitStatus
import run.ostrom.app.domain.models.GitHubLinkState
import run.ostrom.app.domain.models.GitHubPoll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The demo's small repositories: what each answers from memory, what each refuses, and what an answer changes. */
class DemoRepositoriesTest {
    private val clock = FixedClock(10_000_000L)
    private val demo = DemoMode(clock).also { it.start() }
    private val account = DemoContent.ACCOUNT_ID

    @Test fun theSpendingOfAWeekIsAQuarterOfTheMonthsAndNamesWhoWentOver() = runTest {
        val repository = DemoSpendingRepository(demo)
        val month = repository.report(account, SpendPeriod.MONTH)
        val week = repository.report(account, SpendPeriod.WEEK)
        assertEquals(412.80, month.totalUsd, 0.001)
        assertEquals(412.80 / 4, week.totalUsd, 0.001)
        assertEquals(listOf(84, 37), month.byAgent.map { it.sessions })
        assertEquals(listOf(21, 9), week.byAgent.map { it.sessions })
        assertEquals(listOf(51, 44, 26), month.byRepo.map { it.sessions })
        assertEquals(listOf(12, 11, 7), week.byRepo.map { it.sessions })
        assertEquals(listOf(69, 52), month.byPerson.map { it.sessions })
        assertEquals(listOf(17, 13), week.byPerson.map { it.sessions })
        assertEquals("m-ada", month.exceeded.single().subject)
        demo.end()
        assertFailsWith<AppException.NotFound> { repository.report(account, SpendPeriod.MONTH) }
    }

    @Test fun theActivityIsOnePageNewestFirstAndNothingAfterIt() = runTest {
        val repository = DemoActivityRepository(demo)
        val page = repository.page(account)
        assertEquals(listOf("act-1", "act-2", "act-3", "act-4", "act-5"), page.items.map { it.id })
        assertEquals(DemoContent.PUSH_APPROVAL_ID, page.items.first().approvalId)
        assertNull(page.next)
        assertTrue(repository.page(account, before = "act-5").items.isEmpty())
    }

    @Test fun theInboxHoldsTwoPendingRequestsAndAnAnsweredOne() = runTest {
        val repository = DemoApprovalRepository(demo, clock)
        val queue = repository.queue(account)
        assertEquals(listOf(DemoContent.PUSH_APPROVAL_ID, "demo-tool"), queue.pending.map { it.id })
        assertEquals(listOf("demo-merge"), queue.recent.map { it.id })
        assertEquals("demo-tool", repository.view(account, "demo-tool").id)
        assertEquals("demo-merge", repository.view(account, "demo-merge").id)
        assertFailsWith<AppException.NotFound> { repository.view(account, "ghost") }
    }

    @Test fun anApprovalMovesTheRequestToTheAnsweredOnesWithTheNoteAndWho() = runTest {
        val repository = DemoApprovalRepository(demo, clock)
        val approved = repository.answer(account, "demo-tool", ApprovalAnswer.APPROVE, "sha256:demo-tool", "go")
        assertEquals(ApprovalStatus.APPROVED, approved.status)
        assertEquals("ada", approved.decidedBy)
        assertEquals(clock.now, approved.decidedAt)
        assertEquals("go", approved.note)
        val queue = repository.queue(account)
        assertEquals(listOf(DemoContent.PUSH_APPROVAL_ID), queue.pending.map { it.id })
        assertEquals(listOf("demo-tool", "demo-merge"), queue.recent.map { it.id })
        val rejected = repository.answer(account, DemoContent.PUSH_APPROVAL_ID, ApprovalAnswer.REJECT, "sha256:demo-push", null)
        assertEquals(ApprovalStatus.REJECTED, rejected.status)
        assertNull(rejected.note)
    }

    @Test fun anAnswerIsRefusedForAnUnknownClosedOrDifferentlyShownRequest() = runTest {
        val repository = DemoApprovalRepository(demo, clock)
        assertFailsWith<AppException.NotFound> { repository.answer(account, "ghost", ApprovalAnswer.APPROVE, "x", null) }
        val closed = assertFailsWith<AppException.Server> { repository.answer(account, "demo-merge", ApprovalAnswer.APPROVE, "sha256:demo-merge", null) }
        assertEquals(409 to "conflict", closed.statusCode to closed.code)
        val changed = assertFailsWith<AppException.Server> { repository.answer(account, "demo-tool", ApprovalAnswer.APPROVE, "sha256:other", null) }
        assertEquals(409 to "shown_digest_mismatch", changed.statusCode to changed.code)
        repository.unlock(account, "key", null)
    }

    @Test fun theWaitsAskedOfAdaAreListedByStatusNewestFirstAndNothingFromAnotherPage() = runTest {
        val repository = DemoWaitRepository(demo, clock)
        assertEquals(listOf("w1", "w2", "w3"), repository.list(account, WaitStatus.OPEN, null).waits.map { it.id })
        assertEquals(listOf("w4"), repository.list(account, WaitStatus.RESOLVED, null).waits.map { it.id })
        assertEquals(listOf("w5"), repository.list(account, WaitStatus.CANCELLED, null).waits.map { it.id })
        assertTrue(repository.list(account, WaitStatus.OPEN, "c").waits.isEmpty())
    }

    @Test fun whatTheServerWatchesIsInProgressSortedByWhenItIsDue() = runTest {
        val repository = DemoWaitRepository(demo, clock)
        val waiting = repository.inProgress(account)
        assertEquals(listOf("w6", "w7"), waiting.map { it.id })
        assertEquals(listOf(WaitResolver.TIME, WaitResolver.EVENT), waiting.map { it.resolver })
    }

    @Test fun anOpenWaitIsAnsweredAtOnceWithItsActionAndValues() = runTest {
        val repository = DemoWaitRepository(demo, clock)
        val answered = repository.answer(account, "w1", "approve", mapOf("feedback" to JsonPrimitive("fine")))
        assertTrue(!answered.open)
        assertEquals("approve", answered.answer?.action)
        assertEquals(clock.now, answered.resolvedAt)
        assertEquals(listOf("w2", "w3"), repository.list(account, WaitStatus.OPEN, null).waits.map { it.id })
        assertEquals(listOf("w1", "w4"), repository.list(account, WaitStatus.RESOLVED, null).waits.map { it.id })
    }

    @Test fun aWaitIsNotAnsweredWhenUnknownClosedOrWithAnActionItDoesNotOffer() = runTest {
        val repository = DemoWaitRepository(demo, clock)
        assertFailsWith<AppException.NotFound> { repository.answer(account, "ghost", "approve", emptyMap()) }
        assertEquals(409, assertFailsWith<AppException.Server> { repository.answer(account, "w4", "approve", emptyMap()) }.statusCode)
        assertEquals(400, assertFailsWith<AppException.Server> { repository.answer(account, "w1", "dance", emptyMap()) }.statusCode)
    }

    @Test fun theNotificationPreferencesAndSwitchesAreKeptInTheDemosMemory() = runTest {
        val preferences = DemoNotificationPreferencesRepository(demo)
        assertEquals(NotificationPreferences(requests = true, decisions = true, spending = true), preferences.preferences(account))
        val changed = NotificationPreferences(requests = false, decisions = true, spending = false)
        assertEquals(changed, preferences.save(account, changed))
        assertEquals(changed, preferences.preferences(account))
        val settings = DemoNotificationSettingsRepository(demo)
        assertTrue(settings.settings(account).isOn("push", NotificationKind.WAITING))
        val off = settings.set(account, "push", NotificationKind.WAITING, enabled = false)
        assertTrue(!off.isOn("push", NotificationKind.WAITING))
        assertTrue(!settings.settings(account).isOn("push", NotificationKind.WAITING))
        assertEquals(PushTestOutcome.NoPhone, settings.test(account))
    }

    @Test fun theMemberSetupIsFromMemoryAndNeedsTheDemoToRun() = runTest {
        val repository = DemoMemberSetupRepository(demo)
        assertEquals(listOf("DATABASE_URL", "OPENAI_API_KEY", "SENTRY_DSN"), repository.environment(account).names)
        assertEquals(listOf("linear", "notes"), repository.mcpServers(account).map { it.name })
        demo.end()
        assertFailsWith<AppException.NotFound> { repository.mcpServers(account) }
        assertFailsWith<AppException.NotFound> { repository.environment(account) }
    }

    @Test fun theGitHubDeviceFlowIsPendingOnceThenLinksAdasAccountAndUnlinkingRemovesIt() = runTest {
        val repository = DemoGitHubLinkRepository(demo)
        assertEquals(GitHubLinkState.NOT_LINKED, repository.link(account).state)
        val device = repository.start(account)
        assertEquals("DEMO-0000", device.userCode)
        assertEquals(GitHubPoll.Expired, repository.poll(account, "another"))
        assertIs<GitHubPoll.Pending>(repository.poll(account, device.id))
        val linked = assertIs<GitHubPoll.Linked>(repository.poll(account, device.id))
        assertEquals("ada-lovelace", linked.link.login)
        assertEquals(GitHubLinkState.LINKED, repository.link(account).state)
        repository.unlink(account)
        assertEquals(GitHubLinkState.NOT_LINKED, repository.link(account).state)
    }

    @Test fun theGalleryHasTwoPagesAndTheTicketOfAVersionCarriesItsHtml() = runTest {
        val repository = DemoArtifactRepository(demo)
        val page = repository.artifacts(account, null)
        assertEquals(listOf(DemoArtifactIds.PLAN, DemoArtifactIds.ROOT_CAUSE), page.artifacts.map { it.id })
        assertEquals(listOf("team", "personal"), page.artifacts.map { it.access })
        assertEquals(listOf("owner", "team"), page.artifacts.map { it.relation })
        assertTrue(repository.artifacts(account, "c").artifacts.isEmpty())
        assertEquals("Billing migration plan", repository.artifact(account, DemoArtifactIds.PLAN).title)
        assertFailsWith<AppException.NotFound> { repository.artifact(account, "ghost") }
        assertEquals(listOf(2, 1), repository.versions(account, DemoArtifactIds.PLAN).map { it.number })
        assertEquals(listOf(1), repository.versions(account, DemoArtifactIds.ROOT_CAUSE).map { it.number })
        val latest = repository.ticket(account, DemoArtifactIds.PLAN, null)
        assertEquals(2, latest.version)
        assertTrue("behind a flag" in latest.inlineHtml.orEmpty())
        assertEquals(1, repository.ticket(account, DemoArtifactIds.PLAN, 1).version)
        assertFailsWith<AppException.NotFound> { repository.ticket(account, DemoArtifactIds.PLAN, 3) }
        assertFailsWith<AppException.NotFound> { repository.ticket(account, DemoArtifactIds.PLAN, 0) }
        assertFailsWith<AppException.NotFound> { repository.ticket(account, "ghost", null) }
    }

    @Test fun theSessionsAreFilteredByOpenAndNewestFirstAndOneIsFoundById() = runTest {
        val repository = DemoSessionRepository(demo)
        val all = repository.sessions(account, null, null).sessions
        assertTrue(all.size >= 2)
        assertEquals(all.sortedByDescending { it.startedAt }, all)
        assertTrue(all.all { it.gateAnswers.isEmpty() })
        assertEquals(all.filter { it.open }, repository.sessions(account, true, null).sessions)
        assertEquals(all.filter { !it.open }, repository.sessions(account, false, null).sessions)
        assertTrue(repository.sessions(account, null, "c").sessions.isEmpty())
        assertEquals(DemoSessionIds.OPEN, repository.session(account, DemoSessionIds.OPEN).id)
        assertEquals(1, repository.session(account, DemoSessionIds.OPEN).gateAnswers.size, "the session keeps its answers")
        assertFailsWith<AppException.NotFound> { repository.session(account, "ghost") }
    }

    @Test fun onlyTheOpenSessionHasATodoWithItsFourStates() = runTest {
        val repository = DemoTodoRepository(demo)
        val todo = repository.todo(account, DemoSessionIds.OPEN)!!
        assertEquals(listOf("carry", "test40", "docs", "bench"), todo.items.map { it.id })
        assertNull(repository.todo(account, "another"))
    }

    @Test fun theReceiptsAreSignedByTheDemosKeyAndFoundByIdWithinOnePage() = runTest {
        val repository = DemoReceiptRepository(demo)
        val page = repository.page(account, null)
        assertEquals(
            listOf("rcpt_demo-merge", "rcpt_demo-release", "rcpt_demo-hotfix", "rcpt_demo-task"),
            page.items.map { it.summary?.id },
        )
        assertTrue(repository.page(account, "c").items.isEmpty())
        assertEquals("rcpt_demo-hotfix", repository.get(account, "rcpt_demo-hotfix").summary?.id)
        assertFailsWith<AppException.NotFound> { repository.get(account, "ghost") }
        val key = repository.keys(account).single()
        assertEquals("Ed25519", key.alg)
        assertTrue(key.publicKeyPem.startsWith("-----BEGIN PUBLIC KEY-----"))
    }
}
