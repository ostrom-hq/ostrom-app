// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.app.domain.models

/** The period a spending report covers. */
enum class SpendPeriod(val wire: String) { WEEK("week"), MONTH("month") }

/** What one agent, repository or person cost over the period. */
data class SpendLine(val key: String, val label: String, val usd: Double, val sessions: Int)

/** A budget spent past its limit in its current day or month, as the server reports it. */
data class ExceededBudget(val scope: String, val subject: String?, val label: String?, val period: String, val usd: Double, val hardStop: Boolean)

/** What the organization's agents cost over a [period] (`GET /api/v1/spending`). */
data class SpendingReport(
    val period: SpendPeriod,
    val totalUsd: Double,
    val budgetUsd: Double?,
    val byAgent: List<SpendLine>,
    val byRepo: List<SpendLine>,
    val byPerson: List<SpendLine>,
    val exceeded: List<ExceededBudget> = emptyList(),
) {
    /** The budget of the period and how much of it is spent; none when the server sets none. */
    fun budget(): Budget? = budgetUsd?.takeIf { it > 0 }?.let { usd -> (totalUsd / usd).let { ratio -> Budget(usd, ratio, ratio >= BUDGET_WARNING_RATIO) } }

    /**
     * The budgets passed, in the server's order. A member's budget names its member by id: the login comes from
     * [byPerson], whose key is the same id; a member with no line in the period is left unnamed. [myLogin] tells the
     * person's own budget from another's.
     */
    fun exceededLines(myLogin: String?): List<ExceededLine> {
        val names = byPerson.associate { it.key to it.label }
        return exceeded.map { budget ->
            val name = budget.label ?: budget.subject?.let(names::get)
            val who = when {
                budget.scope != "member" -> ExceededWho.Organization
                name != null && name == myLogin -> ExceededWho.Me
                else -> ExceededWho.Member(name)
            }
            ExceededLine(who, if (budget.period == "day") BudgetPeriod.DAY else BudgetPeriod.MONTH, budget.usd, budget.hardStop)
        }
    }

    /** The three breakdowns, each biggest first with its share of the biggest line, for the bars. */
    fun breakdowns(): List<Breakdown> = listOf(
        Breakdown(BreakdownKind.AGENT, shares(byAgent)), Breakdown(BreakdownKind.REPOSITORY, shares(byRepo)), Breakdown(BreakdownKind.PERSON, shares(byPerson)),
    )

    private fun shares(lines: List<SpendLine>): List<SharedLine> {
        val sorted = lines.sortedByDescending { it.usd }
        val top = sorted.firstOrNull()?.usd ?: 0.0
        return sorted.map { SharedLine(it, if (top > 0) it.usd / top else 0.0) }
    }

    companion object {
        /** From this share of the budget the total turns amber: budget is the only use of amber (brand rule). */
        const val BUDGET_WARNING_RATIO = 0.8
    }
}

/** A budget: [ratio] is spent over budget, above 1 once passed; [warning] from [SpendingReport.BUDGET_WARNING_RATIO]. */
data class Budget(val usd: Double, val ratio: Double, val warning: Boolean)

enum class BudgetPeriod { DAY, MONTH }

/** Whose budget was passed: the person's own, another member's (by login when known), or the organization's. */
sealed interface ExceededWho {
    data object Me : ExceededWho
    data class Member(val name: String?) : ExceededWho
    data object Organization : ExceededWho
}

/** A passed budget for the screen; [stops]: the model calls are refused until the period ends, otherwise only flagged. */
data class ExceededLine(val who: ExceededWho, val period: BudgetPeriod, val usd: Double, val stops: Boolean)

enum class BreakdownKind { AGENT, REPOSITORY, PERSON }

data class SharedLine(val line: SpendLine, val share: Double)

data class Breakdown(val kind: BreakdownKind, val lines: List<SharedLine>)
