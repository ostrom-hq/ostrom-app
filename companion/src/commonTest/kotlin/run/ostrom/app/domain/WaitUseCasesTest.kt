// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/src/commonTest/.../domain/WaitUseCasesTest.kt; see NOTICE.
package run.ostrom.app.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.app.FakeAuthRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FixedClock
import run.ostrom.app.accounts
import run.ostrom.app.infrastructure.credentials.MemoryCredentialStore
import run.ostrom.app.domain.models.AnswerLine
import run.ostrom.app.domain.models.AnswerValue
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.WaitAnswer
import run.ostrom.app.domain.models.FieldInput
import run.ostrom.app.domain.models.FieldType
import run.ostrom.app.domain.models.Wait
import run.ostrom.app.domain.models.WaitAction
import run.ostrom.app.domain.models.WaitField
import run.ostrom.app.domain.models.WaitInputProblem
import run.ostrom.app.domain.models.WaitKind
import run.ostrom.app.domain.models.WaitOption
import run.ostrom.app.domain.models.WaitPage
import run.ostrom.app.domain.models.WaitStatus
import run.ostrom.app.domain.models.WaitSource
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.OrgChange
import run.ostrom.app.domain.repositories.EventRepository
import run.ostrom.app.domain.repositories.WaitRepository
import run.ostrom.app.domain.usecases.auth.AccountGuard
import run.ostrom.app.domain.usecases.auth.RefreshAccountUseCase
import run.ostrom.app.domain.usecases.waits.AnswerWaitUseCase
import run.ostrom.app.domain.usecases.events.OrgFeed
import run.ostrom.app.domain.usecases.waits.FollowWaitsUseCase
import run.ostrom.app.domain.usecases.waits.LoadInProgressWaitsUseCase
import run.ostrom.app.domain.usecases.waits.LoadWaitsUseCase
import run.ostrom.app.domain.usecases.waits.WaitAnswerBuilder
import run.ostrom.app.domain.usecases.waits.WaitAnswerOutcome
import run.ostrom.app.domain.usecases.waits.WaitsSignal
import kotlin.test.*

private fun wait(id: String = "w1", fields: List<WaitField> = emptyList(), actions: List<WaitAction> = listOf(WaitAction("done", "Done"))) =
    Wait(id, WaitSource.WAIT, WaitKind.HUMAN_REQUEST, true, "s1", "Fix the login", null, "m1", "Review the plan", null, emptyList(), fields, actions, 1)

class WaitUseCasesTest {
    private class FakeWaits : WaitRepository {
        val calls = mutableListOf<String>()
        var page = WaitPage(emptyList(), null)
        var failure: Exception? = null
        override suspend fun list(accountId: String, status: WaitStatus, before: String?): WaitPage { calls += "list ${status.wire} ${before.orEmpty()}".trim(); return page }
        override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait {
            calls += "answer $waitId $action ${values.entries.joinToString(",") { "${it.key}=${it.value}" }}"
            failure?.let { throw it }
            return wait(waitId).copy(open = false)
        }
    }

    private val feedback = WaitField("feedback", FieldType.TEXT, "Feedback")
    private val review = listOf(WaitAction("approve", "Approve"), WaitAction("request_changes", "Request changes", requires = listOf("feedback")))

    private fun problem(wait: Wait, action: String, inputs: Map<String, FieldInput>) =
        assertFailsWith<WaitInputProblem> { WaitAnswerBuilder.build(wait, wait.actions.first { it.id == action }, inputs) }

    @Test fun anAnswerOnlyHoldsTheFilledFieldsAndEachKeepsItsType() {
        val fields = listOf(
            feedback, WaitField("ok", FieldType.CONFIRM, "OK?"), WaitField("n", FieldType.NUMBER, "How many"), WaitField("d", FieldType.DATE, "Day"),
            WaitField("one", FieldType.CHOICE, "One", options = listOf(WaitOption("a", "A"), WaitOption("b", "B"))),
            WaitField("many", FieldType.CHOICE, "Many", multiple = true), WaitField("note", FieldType.INFO, "Note", help = "Read me"), WaitField("empty", FieldType.TEXT, "Empty"),
        )
        val wait = wait(fields = fields)
        val values = WaitAnswerBuilder.build(wait, wait.actions.single(), mapOf(
            "feedback" to FieldInput.Text("Split step 2"), "ok" to FieldInput.Flag(false), "n" to FieldInput.Text(" 12 "), "d" to FieldInput.Text("2028-02-29"),
            "one" to FieldInput.Chosen(setOf("a")), "many" to FieldInput.Chosen(setOf("x", "y")), "empty" to FieldInput.Text("  "),
        ))
        assertEquals(
            mapOf("feedback" to JsonPrimitive("Split step 2"), "ok" to JsonPrimitive(false), "n" to JsonPrimitive(12L), "d" to JsonPrimitive("2028-02-29"),
                "one" to JsonPrimitive("a"), "many" to JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y")))),
            values,
        )
        assertEquals(JsonPrimitive(1.5), WaitAnswerBuilder.build(wait(fields = listOf(WaitField("n", FieldType.NUMBER, "n"))), wait().actions.single(), mapOf("n" to FieldInput.Text("1.5")))["n"])
    }

    @Test fun whatTheActionRequiresIsCheckedBeforeAnythingIsSent() {
        val wait = wait(fields = listOf(feedback), actions = review)
        assertEquals(emptyMap(), WaitAnswerBuilder.build(wait, review[0], emptyMap()), "approving needs no feedback")
        val missing = problem(wait, "request_changes", mapOf("feedback" to FieldInput.Text("   ")))
        assertEquals("feedback", missing.fieldId); assertEquals(WaitInputProblem.Reason.MISSING, missing.reason); assertEquals("Feedback", missing.label)
        val required = wait(fields = listOf(feedback.copy(required = true)))
        assertEquals(WaitInputProblem.Reason.MISSING, problem(required, "done", emptyMap()).reason)
    }

    @Test fun aBadNumberDateOrLengthNamesItsField() {
        fun reason(type: FieldType, text: String, max: Int? = null) =
            problem(wait(fields = listOf(WaitField("f", type, "F", maxLength = max))), "done", mapOf("f" to FieldInput.Text(text))).reason
        assertEquals(WaitInputProblem.Reason.NOT_A_NUMBER, reason(FieldType.NUMBER, "twelve"))
        assertEquals(WaitInputProblem.Reason.NOT_A_NUMBER, reason(FieldType.NUMBER, "NaN"))
        assertEquals(WaitInputProblem.Reason.NOT_A_DATE, reason(FieldType.DATE, "2027-02-29"))
        assertEquals(WaitInputProblem.Reason.NOT_A_DATE, reason(FieldType.DATE, "29/02/2028"))
        assertEquals(WaitInputProblem.Reason.TOO_LONG, reason(FieldType.TEXT, "abcd", max = 3))
    }

    @Test fun aFileIsNotFilledHereButDoesNotBlockWhatDoesNotNeedIt() {
        val optional = wait(fields = listOf(WaitField("f", FieldType.FILE, "Screenshot"), WaitField("s", FieldType.SECRET, "Password")))
        assertEquals(emptyMap(), WaitAnswerBuilder.build(optional, optional.actions.single(), emptyMap()))
        val needed = wait(fields = listOf(WaitField("f", FieldType.FILE, "Screenshot", required = true)))
        assertEquals(WaitInputProblem.Reason.UNSUPPORTED, problem(needed, "done", emptyMap()).reason)
    }

    @Test fun aSecretIsSentAsTypedAndNeverPrintsItsValue() {
        val secret = WaitField("pw", FieldType.SECRET, "Password", required = true)
        val wait = wait(fields = listOf(secret))
        val typed = FieldInput.Secret("S3NTINEL-it's \"q\" \n line2")
        assertEquals(mapOf("pw" to JsonPrimitive(typed.value)), WaitAnswerBuilder.build(wait, wait.actions.single(), mapOf("pw" to typed)), "the value is sent as it was typed, not trimmed or altered")
        assertFalse("S3NTINEL" in typed.toString() + mapOf("pw" to typed).toString(), "a draft printed in a log shows no value")
        assertEquals(FieldInput.Secret("a"), FieldInput.Secret("a"))
        assertEquals(WaitInputProblem.Reason.MISSING, problem(wait, "done", mapOf("pw" to FieldInput.Secret("   "))).reason)
        assertEquals(WaitInputProblem.Reason.MISSING, problem(wait, "done", emptyMap()).reason)
        assertEquals(WaitInputProblem.Reason.TOO_LONG, problem(wait, "done", mapOf("pw" to FieldInput.Secret("é".repeat(2049)))).reason, "4 KiB of bytes")
        assertEquals(emptyMap(), WaitAnswerBuilder.build(wait(fields = listOf(secret.copy(required = false))), wait.actions.single(), emptyMap()), "an optional secret left empty is not sent")
    }

    @Test fun anAnsweredSecretReadsProvidedNeverAValue() {
        val secret = WaitField("pw", FieldType.SECRET, "Password")
        val answered = wait(fields = listOf(secret, WaitField("note", FieldType.TEXT, "Note"))).copy(open = false, answer = WaitAnswer("done", mapOf("pw" to JsonPrimitive("provided"), "note" to JsonPrimitive("ok"))))
        assertEquals(listOf(AnswerLine("Password", AnswerValue.Secret(true)), AnswerLine("Note", AnswerValue.Words("ok"))), answered.answerLines())
        val skipped = answered.copy(answer = WaitAnswer("done", mapOf("pw" to JsonPrimitive("not provided"))))
        assertEquals(AnswerValue.Secret(false), skipped.answerLines().single().value)
    }

    private fun guard() = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), accounts(), MemoryCredentialStore(), FixedClock()))

    @Test fun anAnswerIsSentWithItsValuesAndTheRepositoryNeverSeesAnInvalidOne() = runTest {
        val waits = FakeWaits(); val answer = AnswerWaitUseCase(waits, guard())
        val wait = wait(fields = listOf(feedback), actions = review)
        val outcome = answer("a1", wait, "request_changes", mapOf("feedback" to FieldInput.Text("Split step 2")))
        assertIs<WaitAnswerOutcome.Answered>(outcome)
        assertEquals(listOf("answer w1 request_changes feedback=\"Split step 2\""), waits.calls)
        waits.calls.clear()
        assertIs<WaitAnswerOutcome.Problem>(answer("a1", wait, "request_changes", emptyMap()))
        assertIs<WaitAnswerOutcome.Refused>(answer("a1", wait, "gone", emptyMap()), "an action the wait no longer has")
        assertEquals(emptyList(), waits.calls)
    }

    @Test fun eachFailureOfAnAnswerBecomesTheOutcomeTheScreenCanShow() = runTest {
        val waits = FakeWaits(); val answer = AnswerWaitUseCase(waits, guard()); val wait = wait()
        suspend fun outcome(failure: Exception): WaitAnswerOutcome { waits.failure = failure; return answer("a1", wait, "done", emptyMap()) }
        assertIs<WaitAnswerOutcome.Closed>(outcome(AppException.Server(409, "wait_closed")))
        assertIs<WaitAnswerOutcome.Closed>(outcome(AppException.NotFound()))
        assertIs<WaitAnswerOutcome.Refused>(outcome(AppException.Server(400, "invalid_answer")))
        assertIs<WaitAnswerOutcome.Retry>(outcome(AppException.Server(503, null)))
        assertIs<WaitAnswerOutcome.Retry>(outcome(AppException.Unreachable()))
        assertIs<WaitAnswerOutcome.Retry>(outcome(AppException.RateLimited(20)))
        assertIs<WaitAnswerOutcome.Failed>(outcome(AppException.Server(403, "wait_assignee_only")))
        waits.failure = AppException.Unauthorized()
        assertFailsWith<AppException.Unauthorized> { answer("a1", wait, "done", emptyMap()) }
    }

    @Test fun theSectionIsHiddenFromAServerWithoutWaits() = runTest {
        val waits = FakeWaits(); val features = FakeFeatures()
        val load = LoadWaitsUseCase(waits, features, guard())
        assertFailsWith<AppException.Unsupported> { load("a1") }
        assertEquals(emptyList(), waits.calls)
        features.features = setOf("waits.v1"); waits.page = WaitPage(listOf(wait()), null)
        assertEquals(listOf("w1"), load("a1").waits.map { it.id })
    }

    @Test fun aFieldLeftAloneSendsItsDefaultAndARequiredBoxMustBeChecked() {
        val fields = listOf(
            WaitField("n", FieldType.NUMBER, "How many", default = JsonPrimitive(3)),
            WaitField("store", FieldType.CHOICE, "Store", default = JsonPrimitive("ios"), options = listOf(WaitOption("ios", "iOS"))),
            WaitField("tags", FieldType.CHOICE, "Tags", multiple = true, default = JsonArray(listOf(JsonPrimitive("a")))),
            WaitField("notify", FieldType.CONFIRM, "Notify", default = JsonPrimitive(true)),
            WaitField("optional", FieldType.CONFIRM, "Optional"),
            WaitField("nothing", FieldType.TEXT, "Nothing", default = kotlinx.serialization.json.JsonNull),
        )
        val wait = wait(fields = fields)
        assertEquals(
            mapOf("n" to JsonPrimitive(3L), "store" to JsonPrimitive("ios"), "tags" to JsonArray(listOf(JsonPrimitive("a"))), "notify" to JsonPrimitive(true), "optional" to JsonPrimitive(false)),
            WaitAnswerBuilder.build(wait, wait.actions.single(), emptyMap()),
        )
        val consent = wait(fields = listOf(WaitField("ok", FieldType.CONFIRM, "I agree", required = true)))
        assertEquals(WaitInputProblem.Reason.MISSING, problem(consent, "done", emptyMap()).reason)
        assertEquals(WaitInputProblem.Reason.MISSING, problem(consent, "done", mapOf("ok" to FieldInput.Flag(false))).reason)
        assertEquals(mapOf("ok" to JsonPrimitive(true)), WaitAnswerBuilder.build(consent, consent.actions.single(), mapOf("ok" to FieldInput.Flag(true))))
    }

    private class FakeEvents(vararg val updates: LiveUpdate<OrgChange>) : EventRepository {
        val asked = mutableListOf<Set<String>>()
        override fun changes(accountId: String, types: Set<String>): Flow<LiveUpdate<OrgChange>> { asked += types; return flowOf(*updates) }
    }

    @Test fun theWaitsLoadOnceFromAServerWithoutTheFeed() = runTest {
        val features = FakeFeatures(); val events = FakeEvents()
        assertEquals(emptyList(), FollowWaitsUseCase(OrgFeed(events, features, backgroundScope), features, guard())("a1").toList(), "nothing to load from a server without waits")
        features.features = setOf("waits.v1")
        assertEquals(listOf(WaitsSignal.Reload), FollowWaitsUseCase(OrgFeed(events, features, backgroundScope), features, guard())("a1").toList())
        assertEquals(emptyList(), events.asked, "No feed asked of a server that does not serve it")
    }

    @Test fun theWaitsFollowTheFeed() = runTest {
        val events = FakeEvents(
            LiveUpdate.Opened(resumed = false),
            LiveUpdate.Item(OrgChange(OrgChange.WAIT_CREATED, "w1", "m1")),
            LiveUpdate.Reconnecting(2_000),
            LiveUpdate.Opened(resumed = true),
            LiveUpdate.Gap,
            LiveUpdate.Item(OrgChange(OrgChange.WAIT_RESOLVED, "w1", "m1")),
        )
        val features = FakeFeatures(setOf("waits.v1", "events.v1"))
        val signals = FollowWaitsUseCase(OrgFeed(events, features, backgroundScope), features, guard())("a1").toList()
        assertEquals(
            listOf(
                WaitsSignal.Connection(false), WaitsSignal.Reload, WaitsSignal.Reload,
                WaitsSignal.Connection(true), WaitsSignal.Connection(false), WaitsSignal.Reload, WaitsSignal.Reload,
            ),
            signals,
            "A load once open, at each wait event and after a gap; none for a connection that resumed",
        )
        assertEquals(listOf(OrgChange.WAIT_TYPES), events.asked)
    }

    @Test fun theWaitsKeepTheirTypesOfTheSharedFeed() = runTest {
        val events = FakeEvents(
            LiveUpdate.Opened(resumed = false),
            LiveUpdate.Item(OrgChange(OrgChange.RUN_UPDATED, "r1", "m1", "wf1")),
            LiveUpdate.Item(OrgChange(OrgChange.TODO_UPDATED, "s1", "m1")),
            LiveUpdate.Item(OrgChange(OrgChange.WAIT_CANCELLED, "w1", "m1")),
        )
        val features = FakeFeatures(setOf("waits.v1", "events.v1", "workflows.v1", "todos.v1"))
        val signals = FollowWaitsUseCase(OrgFeed(events, features, backgroundScope), features, guard())("a1").toList()
        assertEquals(listOf(WaitsSignal.Connection(false), WaitsSignal.Reload, WaitsSignal.Reload), signals, "A run or a todo event does not load the waits again")
        assertEquals(listOf(OrgChange.WAIT_TYPES + OrgChange.RUN_UPDATED + OrgChange.TODO_UPDATED), events.asked, "One connection for every type the app reads")
    }

    @Test fun theInProgressWaitsAreHiddenFromAServerWithoutWaits() = runTest {
        val waits = FakeWaits(); val features = FakeFeatures()
        val load = LoadInProgressWaitsUseCase(waits, features, guard())
        assertFailsWith<AppException.Unsupported> { load("a1") }
        features.features = setOf("waits.v1")
        assertEquals(emptyList(), load("a1"))
    }

    @Test fun ostromsBooleanFieldIsAYesOrNo() {
        assertEquals(FieldType.CONFIRM, FieldType.of("boolean"))
        assertEquals(FieldType.UNKNOWN, FieldType.of("signature"))
        assertEquals(WaitKind.HUMAN_REQUEST, WaitKind.of("workflow"))
    }
}
