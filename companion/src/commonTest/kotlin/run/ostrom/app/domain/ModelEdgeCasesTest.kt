// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.app.domain.models.Account
import run.ostrom.app.domain.models.AnswerLine
import run.ostrom.app.domain.models.AnswerValue
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSessionStatus
import run.ostrom.app.domain.models.FieldInput
import run.ostrom.app.domain.models.FieldType
import run.ostrom.app.domain.models.NewDeviceSession
import run.ostrom.app.domain.models.PermissionMode
import run.ostrom.app.domain.models.SignInMethod
import run.ostrom.app.domain.models.SpendLine
import run.ostrom.app.domain.models.SpendPeriod
import run.ostrom.app.domain.models.SpendingReport
import run.ostrom.app.domain.models.TransferState
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.TurnPhase
import run.ostrom.app.domain.models.Wait
import run.ostrom.app.domain.models.WaitAction
import run.ostrom.app.domain.models.WaitAnswer
import run.ostrom.app.domain.models.WaitField
import run.ostrom.app.domain.models.WaitKind
import run.ostrom.app.domain.models.WaitOption
import run.ostrom.app.domain.models.WaitSource
import run.ostrom.app.domain.models.initialInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelEdgeCasesTest {
    // --- Accounts as the device keeps them ---

    @Test
    fun anAccountKeptWithOnlyItsRequiredFieldsReadsWithItsDefaults() {
        val read = Json.decodeFromString(Account.serializer(), """{"id":"a1","origin":"https://x","login":"ada"}""")
        assertEquals(Account("a1", "https://x", "ada"), read)
        assertTrue(read.signedIn)
        assertEquals(SignInMethod.BROWSER, read.method)
    }

    @Test
    fun anAccountKeptWithEveryFieldReadsBackTheSame() {
        val full = Account("a1", "https://x", "ada", "Ada", "o1", "Acme", 99, "1.4.0", SignInMethod.DEVICE, signedIn = false, memberId = "m1")
        assertEquals(full, Json.decodeFromString(Account.serializer(), Json.encodeToString(Account.serializer(), full)))
    }

    @Test
    fun anAccountWrittenWithEveryDefaultSpelledOutReadsBackTheSame() {
        val spelled = Json { encodeDefaults = true }
        val minimal = Account("a1", "https://x", "ada")
        val text = spelled.encodeToString(Account.serializer(), minimal)
        assertTrue("\"signedIn\":true" in text, text)
        assertEquals(minimal, Json.decodeFromString(Account.serializer(), text))
    }

    @Test
    fun anAccountKeptWithoutItsLoginIsNotRead() {
        assertFailsWith<SerializationException> { Json.decodeFromString(Account.serializer(), """{"id":"a1","origin":"https://x"}""") }
    }

    @Test
    fun anAccountIsShownByItsNameElseItsLogin() {
        assertEquals("Ada", Account("a1", "https://x", "ada", name = "Ada").displayName)
        assertEquals("ada", Account("a1", "https://x", "ada", name = " ").displayName)
        assertEquals("ada", Account("a1", "https://x", "ada").displayName)
    }

    // --- Wire values ---

    @Test
    fun aStateThisVersionDoesNotKnowIsUnknown() {
        assertEquals(TransferState.ACCEPTED, TransferState.of("accepted"))
        assertEquals(TransferState.UNKNOWN, TransferState.of("unknown"))
        assertEquals(TransferState.UNKNOWN, TransferState.of(null))
        assertEquals(CloudSessionStatus.PAUSED, CloudSessionStatus.of("paused"))
        assertEquals(CloudSessionStatus.UNKNOWN, CloudSessionStatus.of(CloudSessionStatus.UNKNOWN.wire))
        assertEquals(CloudSessionStatus.UNKNOWN, CloudSessionStatus.of("later"))
        assertEquals(TurnPhase.FAILED, TurnPhase.of("failed"))
        assertEquals(TurnPhase.UNKNOWN, TurnPhase.of("unknown"))
        assertEquals(TurnPhase.UNKNOWN, TurnPhase.of(null))
        assertEquals(FieldType.CONFIRM, FieldType.of("boolean"))
        assertEquals(FieldType.UNKNOWN, FieldType.of(""))
        assertEquals(FieldType.UNKNOWN, FieldType.of("color"))
    }

    @Test
    fun onlyAnOfferOrAnAcceptedTransferIsOpen() {
        assertEquals(setOf(TransferState.OFFERED, TransferState.ACCEPTED), TransferState.entries.filter { it.open }.toSet())
    }

    @Test
    fun onlyACompletedInterruptedOrFailedTurnIsFinished() {
        assertEquals(setOf(TurnPhase.COMPLETED, TurnPhase.INTERRUPTED, TurnPhase.FAILED), TurnPhase.entries.filter { it.finished }.toSet())
    }

    // --- Sessions ---

    @Test
    fun aRemoteSessionWaitsForItsDeviceOnlyWhileNeitherPausedNorEnded() {
        val waiting = cloudSession(remote = true, phase = "waiting_host", status = CloudSessionStatus.RUNNING)
        assertTrue(waiting.waitingForHost)
        assertFalse(waiting.copy(status = CloudSessionStatus.PAUSED).waitingForHost)
        assertFalse(waiting.copy(status = CloudSessionStatus.TERMINATED).waitingForHost)
        assertFalse(waiting.copy(phase = "cloning").waitingForHost)
        assertFalse(cloudSession(remote = false, phase = "waiting_host").waitingForHost)
    }

    @Test
    fun aPauseIsOfferedOnlyWhenNeitherAPauseNorAStopIsAsked() {
        assertTrue(cloudSession(desired = "running").canPause)
        assertFalse(cloudSession(desired = "paused").canPause)
        assertFalse(cloudSession(desired = "terminated").canPause)
        assertFalse(cloudSession(status = CloudSessionStatus.PAUSED).canPause)
        assertFalse(cloudSession(kind = run.ostrom.app.domain.models.SessionKind.LOCAL).canPause)
    }

    @Test
    fun aDeviceSessionsFormIsTrimmedAndAResumeKeptOnlyForClaude() {
        val form = NewDeviceSession(" Fix ", "d1", "f1", "codex", " gpt-5 ", "  ", PermissionMode.BYPASS, " ", resumeSessionId = "r1")
        assertEquals(NewDeviceSession("Fix", "d1", "f1", "codex", "gpt-5", null, PermissionMode.BYPASS, null, null), form.normalized())
        assertEquals("r1", form.copy(agent = "claude", effort = "high").normalized().resumeSessionId)
        assertEquals("high", form.copy(effort = " high ").normalized().effort)
        assertNull(form.copy(model = null).normalized().model)
    }

    // --- Spending ---

    @Test
    fun linesThatCostNothingAllHaveNoShare() {
        val report = SpendingReport(SpendPeriod.WEEK, 0.0, null, listOf(SpendLine("claude", "Claude", 0.0, 1)), emptyList(), emptyList())
        assertEquals(listOf(0.0), report.breakdowns().first().lines.map { it.share })
        assertTrue(report.breakdowns()[1].lines.isEmpty())
    }

    // --- Errors as the screen words them ---

    @Test
    fun aRefusalNamesItsCodeOnlyWhenItIsAStableKey() {
        assertEquals("The server refused the transfer (transfer_pending).", AppException.TransferRefused("transfer_pending").message)
        assertEquals("The server refused the transfer.", AppException.TransferRefused("Not A Key!").message)
        assertEquals("The file was refused.", AppException.FileRefused(null, 400).message)
        assertEquals("The file was refused (too_big).", AppException.FileRefused("too_big", 413).message)
        assertEquals("The server returned HTTP 409 (conflict).", AppException.Server(409, "conflict").message)
        assertTrue(AppException.InstallationBusy().message!!.contains("installation-id.lock"))
    }

    @Test
    fun everyDeviceRefusalIsWorded() {
        // Each code the app knows reads differently.
        val known = AppException.DeviceRefused.CODES.map { AppException.DeviceRefused(it).message!! }
        assertEquals(AppException.DeviceRefused.CODES.size, known.toSet().size)
        assertEquals("Choose one of the conversations the device listed.", AppException.DeviceRefused("invalid_resume").message)
        assertEquals("The server refused to start the session on this device (something_new).", AppException.DeviceRefused("something_new").message)
        assertEquals("The server refused to start the session on this device.", AppException.DeviceRefused("Bad Code").message)
        assertNotEquals(AppException.DeviceRefused("invalid_folder").message, AppException.DeviceRefused("folder_not_published").message)
    }

    // --- Transcript events ---

    @Test
    fun errorAndTransferEventsCarryWhatTheServerSaid() {
        val error = TranscriptEvent.Error(3, 30, "runner_crashed", "The runner stopped.")
        assertEquals(TranscriptEvent.Error(3, 30, "runner_crashed", "The runner stopped."), error)
        assertEquals(3, error.seq)
        val transferred = TranscriptEvent.Transferred(4, 40, "m1", "m2")
        assertEquals("m2", transferred.toMemberId)
        assertEquals(40, transferred.at)
    }

    // --- A wait's answer, as the history shows it ---

    private fun wait(fields: List<WaitField>, answer: WaitAnswer?, actions: List<WaitAction> = listOf(WaitAction("approve", "Approve"))) =
        Wait("w1", WaitSource.WAIT, WaitKind.HUMAN_REQUEST, false, null, null, null, "m1", "Ship?", null, emptyList(), fields, actions, 1, answer = answer)

    @Test
    fun theAnswersActionIsShownInTheWaitsWordsElseByItsId() {
        assertEquals("Approve", wait(emptyList(), WaitAnswer("approve")).answerActionLabel)
        assertEquals("reject", wait(emptyList(), WaitAnswer("reject")).answerActionLabel)
        assertNull(wait(emptyList(), null).answerActionLabel)
    }

    @Test
    fun eachValueIsShownByItsKind() {
        val fields = listOf(
            WaitField("token", FieldType.SECRET, "Token"),
            WaitField("given", FieldType.SECRET, "Given"),
            WaitField("ok", FieldType.CONFIRM, "OK"),
            WaitField("color", FieldType.CHOICE, "Color", options = listOf(WaitOption("r", "Red"))),
            WaitField("many", FieldType.CHOICE, "Many", options = listOf(WaitOption("r", "Red")), multiple = true),
            WaitField("note", FieldType.INFO, "Note"),
            WaitField("word", FieldType.TEXT, "Word"),
            WaitField("count", FieldType.NUMBER, "Count"),
            WaitField("obj", FieldType.TEXT, "Object"),
        )
        val values = mapOf(
            "token" to JsonPrimitive("provided"), "given" to JsonArray(emptyList()), "ok" to JsonPrimitive(true), "color" to JsonPrimitive("r"),
            "many" to JsonArray(listOf(JsonPrimitive("r"), JsonPrimitive("x"), JsonArray(emptyList()))), "note" to JsonPrimitive("hidden"),
            "word" to JsonPrimitive("true"), "count" to JsonPrimitive(3), "obj" to JsonObject(mapOf("a" to JsonPrimitive(1))),
            "gone" to JsonPrimitive(false), "loose" to JsonPrimitive("r"),
        )
        assertEquals(
            listOf(
                AnswerLine("Token", AnswerValue.Secret(true)),
                AnswerLine("Given", AnswerValue.Secret(false)),
                AnswerLine("OK", AnswerValue.Flag(true)),
                AnswerLine("Color", AnswerValue.Words("Red")),
                AnswerLine("Many", AnswerValue.Words("Red, x, []")),
                AnswerLine("Word", AnswerValue.Words("true")),
                AnswerLine("Count", AnswerValue.Words("3")),
                AnswerLine("Object", AnswerValue.Words("""{"a":1}""")),
                AnswerLine("gone", AnswerValue.Flag(false)),
                // A field the wait no longer shows has no options to word its value.
                AnswerLine("loose", AnswerValue.Words("r")),
            ),
            wait(fields, WaitAnswer("approve", values)).answerLines(),
        )
        assertEquals(emptyList(), wait(fields, null).answerLines())
    }

    @Test
    fun aFieldsFirstInputComesFromItsDefault() {
        assertEquals(FieldInput.Chosen(setOf("a", "b")), WaitField("c", FieldType.CHOICE, "C", default = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"), JsonArray(emptyList())))).initialInput())
        assertEquals(FieldInput.Chosen(setOf("a")), WaitField("c", FieldType.CHOICE, "C", default = JsonPrimitive("a")).initialInput())
        assertNull(WaitField("c", FieldType.CHOICE, "C").initialInput())
        assertNull(WaitField("s", FieldType.SECRET, "S", default = JsonPrimitive("x")).initialInput())
        assertEquals(FieldInput.Text("x"), WaitField("t", FieldType.TEXT, "T", default = JsonPrimitive("x")).initialInput())
        assertEquals(FieldInput.Text("4"), WaitField("n", FieldType.NUMBER, "N", default = JsonPrimitive(4)).initialInput())
        assertNull(WaitField("t", FieldType.TEXT, "T", default = JsonNull).initialInput())
        assertNull(WaitField("t", FieldType.TEXT, "T").initialInput())
        assertEquals(FieldInput.Flag(false), WaitField("b", FieldType.CONFIRM, "B").initialInput())
        assertEquals(FieldInput.Flag(true), WaitField("b", FieldType.CONFIRM, "B", default = JsonPrimitive(true)).initialInput())
        assertEquals(FieldInput.Text("2026-11-18"), WaitField("d", FieldType.DATE, "D", default = JsonPrimitive("2026-11-18")).initialInput())
        // A file is attached and a note only read: neither starts with a value.
        assertNull(WaitField("f", FieldType.FILE, "F", default = JsonPrimitive("x")).initialInput())
        assertNull(WaitField("i", FieldType.INFO, "I", default = JsonPrimitive("x")).initialInput())
    }

    @Test
    fun aSecretInputComparesByValueAndNeverPrintsIt() {
        assertEquals(FieldInput.Secret("a"), FieldInput.Secret("a"))
        assertNotEquals(FieldInput.Secret("a"), FieldInput.Secret("b"))
        assertFalse(FieldInput.Secret("a").equals(FieldInput.Text("a")))
        assertEquals("a".hashCode(), FieldInput.Secret("a").hashCode())
        assertEquals("Secret(***)", FieldInput.Secret("a").toString())
    }
}
