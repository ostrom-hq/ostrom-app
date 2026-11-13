// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FixedClock
import run.ostrom.app.accounts
import run.ostrom.app.domain.models.ActivityItem
import run.ostrom.app.domain.models.ActivityPage
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.BreakdownKind
import run.ostrom.app.domain.models.BudgetPeriod
import run.ostrom.app.domain.models.ExceededBudget
import run.ostrom.app.domain.models.ExceededWho
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.SpendLine
import run.ostrom.app.domain.models.SpendPeriod
import run.ostrom.app.domain.models.SpendingReport
import run.ostrom.app.domain.repositories.ActivityRepository
import run.ostrom.app.domain.repositories.SpendingRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.domain.usecases.spending.LoadActivityUseCase
import run.ostrom.app.domain.usecases.spending.LoadSpendingUseCase
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpendingUseCasesTest {
    private fun report(total: Double = 80.0, budget: Double? = 100.0, exceeded: List<ExceededBudget> = emptyList()) = SpendingReport(
        period = SpendPeriod.MONTH,
        totalUsd = total,
        budgetUsd = budget,
        byAgent = listOf(SpendLine("codex", "Codex", 20.0, 2), SpendLine("claude-code", "Claude Code", 60.0, 5)),
        byRepo = emptyList(),
        byPerson = listOf(SpendLine("m-ada", "ada", 50.0, 4), SpendLine("m-charles", "charles", 30.0, 3)),
        exceeded = exceeded,
    )

    private fun guard() = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts(), MemoryCredentialStore(), FixedClock()))

    @Test
    fun theBudgetTurnsAmberFromEightyPercentAndIsAbsentWithoutOne() {
        val budget = report(total = 80.0).budget()!!
        assertEquals(0.8, budget.ratio)
        assertTrue(budget.warning)
        assertFalse(report(total = 79.0).budget()!!.warning)
        assertTrue(report(total = 130.0).budget()!!.ratio > 1)
        assertNull(report(budget = null).budget())
        assertNull(report(budget = 0.0).budget(), "a zero budget is none")
    }

    @Test
    fun eachBreakdownIsBiggestFirstWithItsShareOfTheBiggest() {
        val breakdowns = report().breakdowns()
        assertEquals(listOf(BreakdownKind.AGENT, BreakdownKind.REPOSITORY, BreakdownKind.PERSON), breakdowns.map { it.kind })
        val agents = breakdowns.first().lines
        assertEquals(listOf("claude-code", "codex"), agents.map { it.line.key })
        assertEquals(listOf(1.0, 20.0 / 60.0), agents.map { it.share })
        assertTrue(breakdowns[1].lines.isEmpty())
    }

    @Test
    fun aPassedBudgetSaysWhoseItIsByLoginWhenKnown() {
        val lines = report(
            exceeded = listOf(
                ExceededBudget("member", "m-ada", null, "day", 25.0, hardStop = true),
                ExceededBudget("member", "m-charles", null, "month", 100.0, hardStop = false),
                ExceededBudget("member", "m-gone", null, "day", 10.0, hardStop = false),
                ExceededBudget("member", "m-x", "grace", "day", 10.0, hardStop = false),
                ExceededBudget("organization", null, null, "month", 500.0, hardStop = true),
            ),
        ).exceededLines(myLogin = "ada")
        assertEquals(
            listOf(ExceededWho.Me, ExceededWho.Member("charles"), ExceededWho.Member(null), ExceededWho.Member("grace"), ExceededWho.Organization),
            lines.map { it.who },
        )
        assertEquals(listOf(BudgetPeriod.DAY, BudgetPeriod.MONTH, BudgetPeriod.DAY, BudgetPeriod.DAY, BudgetPeriod.MONTH), lines.map { it.period })
        assertEquals(listOf(true, false, false, false, true), lines.map { it.stops })
        assertEquals(25.0, lines.first().usd)
    }

    private class FakeSpending : SpendingRepository, ActivityRepository {
        val calls = mutableListOf<String>()
        override suspend fun report(accountId: String, period: SpendPeriod): SpendingReport {
            calls += "report ${period.wire}"
            return SpendingReport(period, 1.0, null, emptyList(), emptyList(), emptyList())
        }

        override suspend fun page(accountId: String, before: String?): ActivityPage {
            calls += "page ${before.orEmpty()}".trim()
            return ActivityPage(listOf(ActivityItem("i1", 1L, "session.ended", "agent", "Codex", "Done", null, null, null)), "c2")
        }
    }

    @Test
    fun theSpendingIsHiddenFromAServerWithoutBudgets() = runTest {
        val spending = FakeSpending(); val features = FakeFeatures()
        val load = LoadSpendingUseCase(spending, features, guard())
        assertFailsWith<AppException.Unsupported> { load("a1", SpendPeriod.MONTH) }
        assertEquals(emptyList(), spending.calls)
        features.features = setOf(Features.BUDGETS)
        assertEquals(SpendPeriod.WEEK, load("a1", SpendPeriod.WEEK).period)
        assertEquals(listOf("report week"), spending.calls)
    }

    @Test
    fun theActivityIsReadPageByPage() = runTest {
        val activity = FakeSpending(); val load = LoadActivityUseCase(activity, guard())
        val first = load("a1")
        assertEquals("c2", first.next)
        assertTrue(first.items.single().byAgent)
        load("a1", "c2")
        assertEquals(listOf("page", "page c2"), activity.calls)
    }
}
