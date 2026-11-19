// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.app.FakeEventRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.FieldInput
import run.ostrom.app.domain.models.FieldType
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.Wait
import run.ostrom.app.domain.models.WaitAction
import run.ostrom.app.domain.models.WaitField
import run.ostrom.app.domain.models.WaitInputProblem
import run.ostrom.app.domain.models.WaitKind
import run.ostrom.app.domain.models.WaitPage
import run.ostrom.app.domain.models.WaitSource
import run.ostrom.app.domain.models.WaitStatus
import run.ostrom.app.domain.repositories.WaitRepository
import run.ostrom.app.domain.usecases.events.OrgFeed
import run.ostrom.app.domain.usecases.waits.AnswerWaitUseCase
import run.ostrom.app.domain.usecases.waits.FollowWaitsUseCase
import run.ostrom.app.domain.usecases.waits.WaitAnswerBuilder
import run.ostrom.app.domain.usecases.waits.WaitAnswerOutcome
import run.ostrom.app.domain.usecases.waits.WaitsAvailabilityUseCase
import run.ostrom.app.domain.usecases.waits.WaitsSignal
import run.ostrom.app.fakeGuard
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WaitAnswerEdgeCasesTest {
    private val done = WaitAction("done", "Done")

    private fun wait(vararg fields: WaitField, actions: List<WaitAction> = listOf(done)) =
        Wait("w1", WaitSource.WAIT, WaitKind.HUMAN_REQUEST, true, "s1", "Fix the login", null, "m1", "Review the plan", null, emptyList(), fields.toList(), actions, 1)

    private fun build(field: WaitField, input: FieldInput?): Map<String, JsonElement> =
        WaitAnswerBuilder.build(wait(field), done, if (input == null) emptyMap() else mapOf(field.id to input))

    private fun reason(field: WaitField, input: FieldInput?) = assertFailsWith<WaitInputProblem> { build(field, input) }.reason

    // --- Secrets ---

    @Test
    fun aSecretIsSentWhenGivenAndRefusedWhenTooLongOrWithANul() {
        val secret = WaitField("token", FieldType.SECRET, "Token")
        assertEquals(mapOf<String, JsonElement>("token" to JsonPrimitive("s3cret")), build(secret, FieldInput.Secret("s3cret")))
        assertEquals(WaitInputProblem.Reason.TOO_LONG, reason(secret, FieldInput.Secret("x".repeat(4097))))
        assertEquals(WaitInputProblem.Reason.TOO_LONG, reason(secret, FieldInput.Secret("a\u0000b")))
        assertEquals(JsonPrimitive("x".repeat(4096)), build(secret, FieldInput.Secret("x".repeat(4096)))["token"])
    }

    @Test
    fun aSecretLeftBlankOrTypedAsTextIsMissing() {
        val secret = WaitField("token", FieldType.SECRET, "Token", required = true)
        assertEquals(WaitInputProblem.Reason.MISSING, reason(secret, FieldInput.Secret("  ")))
        assertEquals(WaitInputProblem.Reason.MISSING, reason(secret, FieldInput.Text("s3cret")))
        assertEquals(emptyMap(), build(secret.copy(required = false), null))
    }

    // --- Boxes ---

    @Test
    fun aRequiredBoxMustBeCheckedAndAnOptionalOneSendsItsNo() {
        val consent = WaitField("ok", FieldType.CONFIRM, "I agree", required = true)
        assertEquals(WaitInputProblem.Reason.MISSING, reason(consent, FieldInput.Flag(false)))
        assertEquals(mapOf<String, JsonElement>("ok" to JsonPrimitive(true)), build(consent, FieldInput.Flag(true)))
        assertEquals(mapOf<String, JsonElement>("ok" to JsonPrimitive(false)), build(consent.copy(required = false), FieldInput.Flag(false)))
        assertEquals(WaitInputProblem.Reason.MISSING, reason(consent, FieldInput.Text("yes")))
    }

    // --- Choices ---

    @Test
    fun blankChoicesAreDroppedAndNoneLeftIsMissing() {
        val choice = WaitField("pick", FieldType.CHOICE, "Pick", required = true)
        assertEquals(WaitInputProblem.Reason.MISSING, reason(choice, FieldInput.Chosen(setOf(" ", ""))))
        assertEquals(WaitInputProblem.Reason.MISSING, reason(choice, FieldInput.Text("a")))
        assertEquals(mapOf<String, JsonElement>("pick" to JsonPrimitive("b")), build(choice, FieldInput.Chosen(setOf(" ", "b"))))
        assertEquals(
            mapOf<String, JsonElement>("pick" to JsonArray(listOf(JsonPrimitive("a")))),
            build(choice.copy(multiple = true), FieldInput.Chosen(setOf("a", ""))),
        )
    }

    // --- Numbers ---

    @Test
    fun numbersAreWholeOrDecimalAndNeverInfinite() {
        val number = WaitField("n", FieldType.NUMBER, "How many", required = true)
        assertEquals(mapOf<String, JsonElement>("n" to JsonPrimitive(1.5)), build(number, FieldInput.Text(" 1.5 ")))
        assertEquals(mapOf<String, JsonElement>("n" to JsonPrimitive(-3L)), build(number, FieldInput.Text("-3")))
        assertEquals(WaitInputProblem.Reason.NOT_A_NUMBER, reason(number, FieldInput.Text("Infinity")))
        assertEquals(WaitInputProblem.Reason.NOT_A_NUMBER, reason(number, FieldInput.Text("NaN")))
        assertEquals(WaitInputProblem.Reason.NOT_A_NUMBER, reason(number, FieldInput.Text("twelve")))
        assertEquals(WaitInputProblem.Reason.MISSING, reason(number, FieldInput.Text("   ")))
        assertEquals(WaitInputProblem.Reason.MISSING, reason(number, FieldInput.Flag(true)))
    }

    // --- Dates ---

    @Test
    fun datesAreRealCalendarDays() {
        val date = WaitField("d", FieldType.DATE, "Day", required = true)
        assertEquals(mapOf<String, JsonElement>("d" to JsonPrimitive("2000-02-29")), build(date, FieldInput.Text("2000-02-29")))
        assertEquals(mapOf<String, JsonElement>("d" to JsonPrimitive("2027-12-31")), build(date, FieldInput.Text(" 2027-12-31 ")))
        for (bad in listOf("2100-02-29", "2027-02-29", "2027-13-01", "2027-00-10", "2027-04-31", "2027-04-00", "27-04-01", "tomorrow")) {
            assertEquals(WaitInputProblem.Reason.NOT_A_DATE, reason(date, FieldInput.Text(bad)), bad)
        }
        assertEquals(WaitInputProblem.Reason.MISSING, reason(date, FieldInput.Text("")))
        assertEquals(WaitInputProblem.Reason.MISSING, reason(date, FieldInput.Chosen(setOf("2027-01-01"))))
    }

    // --- Text ---

    @Test
    fun textIsBoundByTheFieldsLengthOrTheDefaultOne() {
        val text = WaitField("t", FieldType.TEXT, "Text", maxLength = 3)
        assertEquals(mapOf<String, JsonElement>("t" to JsonPrimitive("abc")), build(text, FieldInput.Text("abc")))
        assertEquals(WaitInputProblem.Reason.TOO_LONG, reason(text, FieldInput.Text("abcd")))
        assertEquals(WaitInputProblem.Reason.TOO_LONG, reason(text.copy(maxLength = null), FieldInput.Text("x".repeat(8_001))))
        assertEquals(emptyMap(), build(text, FieldInput.Chosen(setOf("abc"))))
    }

    // --- Sending ---

    private class FakeWaits(var failure: Exception? = null) : WaitRepository {
        val answered = mutableListOf<String>()
        override suspend fun list(accountId: String, status: WaitStatus, before: String?) = WaitPage(emptyList(), null)
        override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait {
            answered += "$waitId $action"
            failure?.let { throw it }
            error("not answered in these tests")
        }
    }

    @Test
    fun anActionTheWaitDoesNotHaveIsRefusedWithoutSending() = runTest {
        val waits = FakeWaits()
        assertSame(WaitAnswerOutcome.Refused, AnswerWaitUseCase(waits, fakeGuard())("a1", wait(), "approve", emptyMap()))
        assertTrue(waits.answered.isEmpty())
    }

    @Test
    fun eachFailureOfTheServerTellsWhatToDoNext() = runTest {
        suspend fun outcome(failure: Exception) = AnswerWaitUseCase(FakeWaits(failure), fakeGuard())("a1", wait(), "done", emptyMap())
        assertSame(WaitAnswerOutcome.Closed, outcome(AppException.Server(404, "not_found")))
        assertSame(WaitAnswerOutcome.Closed, outcome(AppException.NotFound()))
        assertSame(WaitAnswerOutcome.Retry, outcome(AppException.Server(408, null)))
        assertSame(WaitAnswerOutcome.Retry, outcome(AppException.Server(502, null)))
        assertSame(WaitAnswerOutcome.Retry, outcome(AppException.RateLimited(null)))
        val forbidden = AppException.Server(403, "forbidden")
        assertEquals(WaitAnswerOutcome.Failed(forbidden), outcome(forbidden))
        val other = IllegalStateException("broken")
        assertEquals(WaitAnswerOutcome.Failed(other), outcome(other))
    }

    @Test
    fun aRefusedSessionIsThrownToTheScreen() = runTest {
        assertFailsWith<AppException.Unauthorized> { AnswerWaitUseCase(FakeWaits(AppException.Unauthorized()), fakeGuard())("a1", wait(), "done", emptyMap()) }
    }

    // --- Availability and the feed ---

    @Test
    fun waitsAreAvailableOnlyWhenTheServerAnnouncesThem() = runTest {
        assertTrue(WaitsAvailabilityUseCase(FakeFeatures(setOf(Features.WAITS)))("a1"))
        assertFalse(WaitsAvailabilityUseCase(FakeFeatures())("a1"))
    }

    @Test
    fun theWaitsFeedIgnoresAnEnd() = runTest {
        val features = FakeFeatures(setOf(Features.EVENTS, Features.WAITS))
        val events = FakeEventRepository(flowOf(LiveUpdate.Opened(resumed = false), LiveUpdate.Ended))
        val signals = FollowWaitsUseCase(OrgFeed(events, features, backgroundScope), features, fakeGuard())("a1").toList()
        assertEquals(listOf(WaitsSignal.Connection(reconnecting = false), WaitsSignal.Reload), signals)
        assertIs<WaitsSignal.Reload>(signals.last())
    }
}
