// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/SessionFilesTest.kt; see NOTICE.
package run.ostrom.app.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeCloudSessionRepository
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FakeFileRepository
import run.ostrom.app.SequenceIds
import run.ostrom.app.conversationViewModel
import run.ostrom.app.domain.cloudSession
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.CloudSessionMode
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.FileProblem
import run.ostrom.app.domain.models.InputReceipt
import run.ostrom.app.domain.models.LiveUpdate
import run.ostrom.app.domain.models.PendingFile
import run.ostrom.app.domain.models.PendingMessage
import run.ostrom.app.domain.models.TranscriptEvent
import run.ostrom.app.domain.models.UploadedFile
import run.ostrom.app.domain.repositories.TranscriptRepository
import run.ostrom.app.domain.usecases.files.SessionFilesUseCase
import run.ostrom.app.domain.models.TranscriptPage
import run.ostrom.app.fakeGuard
import run.ostrom.app.picked
import run.ostrom.app.policy
import run.ostrom.app.presentation.viewmodels.CloudSessionViewModel
import run.ostrom.app.presentation.viewmodels.SessionNotice
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The composer's files: the capability, the upload and its progress, retries, the cap, and the message that carries them. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionFilesTest {
    private val dispatcher = StandardTestDispatcher()
    private val transcripts = object : TranscriptRepository {
        override suspend fun page(accountId: String, sessionId: String, before: Long?) = TranscriptPage(emptyList(), nextBefore = null, lastSeq = 0)
        override fun follow(accountId: String, sessionId: String, after: Long) = emptyFlow<LiveUpdate<TranscriptEvent>>()
    }
    private val sessions = FakeCloudSessionRepository(cloudSession("s1", ownerId = "m1").copy(mode = CloudSessionMode.CONVERSATION))
    private val files = FakeFileRepository()
    private val features = FakeFeatures(setOf(Features.RUNNERS_CLOUD, Features.RUNNERS_CLOUD_CONVERSATION, Features.RUNNERS_CLOUD_FILES))

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(withFiles: Boolean = true): CloudSessionViewModel =
        conversationViewModel(sessions, transcripts, features, files = if (withFiles) SessionFilesUseCase(files, features, fakeGuard(), SequenceIds()) else null)

    private fun TestScope.started(withFiles: Boolean = true) = viewModel(withFiles).also { it.visible(); settle() }

    @Test fun withoutTheCapabilityThereIsNoPaperclipAndMessagesGoAsBefore() = runTest(dispatcher) {
        features.features = features.features - Features.RUNNERS_CLOUD_FILES
        val model = started()
        assertFalse(model.uiState.value.files.supported)
        assertEquals(0, files.policyReads)
        // Picked files reaching the screen anyway (a drop) are ignored.
        model.attach(listOf(picked("a.png"))); settle()
        assertTrue(model.uiState.value.files.files.isEmpty())
        model.onDraftChange("hello"); model.send(); settle()
        assertEquals("send s1 hello ${model.uiState.value.pending.single().clientMessageId}", sessions.calls.last())
        model.hidden()
    }

    @Test fun aTeamWithoutStorageShowsThePaperclipAndExplainsIt() = runTest(dispatcher) {
        files.filePolicy = policy(available = false)
        val model = started()
        assertTrue(model.uiState.value.files.supported)
        assertFalse(model.uiState.value.files.canAttach)
        model.explainFiles()
        assertEquals(FileProblem.StorageNotConfigured, model.uiState.value.files.notice)
        model.dismissFileNotice()
        assertNull(model.uiState.value.files.notice)
        model.hidden()
    }

    @Test fun aPolicyThatCannotBeReadIsReadAgainNextTime() = runTest(dispatcher) {
        files.policyFailure = AppException.Unreachable()
        val model = started()
        assertFalse(model.uiState.value.files.supported)
        model.hidden()
        files.policyFailure = null
        model.visible(); settle()
        assertTrue(model.uiState.value.files.supported)
        assertEquals(2, files.policyReads)
        model.hidden()
    }

    @Test fun aFileUploadsWithItsProgressThenGoesWithTheMessage() = runTest(dispatcher) {
        val model = started()
        val gate = CompletableDeferred<Unit>()
        files.gate = gate; files.progressBefore = 40
        model.attach(listOf(picked("a.png", size = 100))); settle()
        val uploading = model.uiState.value.files.files.single()
        assertEquals(PendingFile.State.UPLOADING, uploading.state)
        assertEquals(40, uploading.sent); assertEquals(0.4f, uploading.progress)
        // Send waits for the upload.
        model.onDraftChange("What is in it?")
        assertFalse(model.uiState.value.canSend)
        model.send()
        assertEquals(SessionNotice.FilesNotReady, model.uiState.value.notice)
        assertTrue(model.uiState.value.pending.isEmpty())
        gate.complete(Unit); settle()
        val uploaded = model.uiState.value.files.files.single()
        assertEquals(PendingFile.State.UPLOADED, uploaded.state)
        assertTrue(model.uiState.value.canSend)
        model.send(); settle()
        val message = model.uiState.value.pending.single()
        assertEquals(listOf(uploaded.uploaded), message.files)
        assertEquals("send s1 What is in it? ${message.clientMessageId} files=${uploaded.uploaded!!.id}", sessions.calls.last())
        // The composer starts over.
        assertTrue(model.uiState.value.files.files.isEmpty())
        assertEquals("", model.uiState.value.draft)
        model.hidden()
    }

    @Test fun filesAloneMakeAMessage() = runTest(dispatcher) {
        val model = started()
        assertFalse(model.uiState.value.canSend)
        model.attach(listOf(picked("a.png"), picked("b.pdf"))); settle()
        assertTrue(model.uiState.value.canSend)
        model.send(); settle()
        val message = model.uiState.value.pending.single()
        assertEquals("", message.text)
        assertEquals(listOf("a.png", "b.pdf"), message.files.map { it.name })
        assertEquals(PendingMessage.State.SENT, message.state)
        model.hidden()
    }

    @Test fun aFailedMessageIsRetriedWithItsFiles() = runTest(dispatcher) {
        val model = started()
        model.attach(listOf(picked("a.png"))); settle()
        sessions.sendResult = { throw AppException.Unreachable() }
        model.send(); settle()
        val failed = model.uiState.value.pending.single()
        assertEquals(PendingMessage.State.FAILED, failed.state)
        sessions.sendResult = { InputReceipt("in-$it", "queued", false, null) }
        model.retry(failed.clientMessageId); settle()
        assertEquals(2, sessions.calls.count { it.endsWith("files=${failed.files.single().id}") })
        model.hidden()
    }

    @Test fun aMessageRefusedForItsFilesKeepsTheCode() = runTest(dispatcher) {
        val model = started()
        model.attach(listOf(picked("a.png"))); settle()
        sessions.sendResult = { throw AppException.Server(409, "file_unavailable") }
        model.send(); settle()
        val refused = model.uiState.value.pending.single()
        assertEquals(PendingMessage.State.REFUSED, refused.state)
        assertEquals("file_unavailable", refused.reason)
        model.hidden()
    }

    @Test fun aTransientFailureRetriesUnderTheSameKeyAndARefusalCannotBeRetried() = runTest(dispatcher) {
        val model = started()
        files.result = { _, _ -> throw AppException.FileRefused("file_storage_unavailable", 503) }
        model.attach(listOf(picked("a.png"))); settle()
        val failed = model.uiState.value.files.files.single()
        assertEquals(PendingFile.State.FAILED, failed.state)
        assertEquals(FileProblem.StorageUnavailable, failed.problem)
        assertFalse(model.uiState.value.canSend)
        files.result = { file, key -> UploadedFile("f-$key", file.name, file.size, "image/png") }
        model.retryFile(failed.localId); settle()
        assertEquals(PendingFile.State.UPLOADED, model.uiState.value.files.files.single().state)
        val (first, second) = files.uploads
        assertEquals(first.second, second.second)

        files.result = { _, _ -> throw AppException.FileRefused("file_type_not_allowed", 415) }
        model.attach(listOf(picked("b.png"))); settle()
        val refused = model.uiState.value.files.files.last()
        assertIs<FileProblem.TypeNotAllowed>(refused.problem)
        model.retryFile(refused.localId); settle()
        assertEquals(3, files.uploads.size)
        assertEquals(PendingFile.State.FAILED, model.uiState.value.files.files.last().state)
        // Removing it lets the message go with the other.
        model.removeFile(refused.localId)
        assertTrue(model.uiState.value.canSend)
        model.hidden()
    }

    @Test fun anIntegrityFailureIsRetriedUnderANewKey() = runTest(dispatcher) {
        val model = started()
        files.result = { _, _ -> throw AppException.FileRefused("file_integrity_failed", 0) }
        model.attach(listOf(picked("a.png"))); settle()
        val failed = model.uiState.value.files.files.single()
        assertEquals(FileProblem.Integrity, failed.problem)
        files.result = { file, key -> UploadedFile("f-$key", file.name, file.size, "image/png") }
        model.retryFile(failed.localId); settle()
        assertEquals(PendingFile.State.UPLOADED, model.uiState.value.files.files.single().state)
        // The same key would replay the bytes the server stored.
        val (first, second) = files.uploads
        assertNotEquals(first.second, second.second)
        model.hidden()
    }

    @Test fun theSixthFileIsRefusedBeforeAnyUpload() = runTest(dispatcher) {
        val model = started()
        model.attach((1..6).map { picked("f$it.png") }); settle()
        assertEquals(5, model.uiState.value.files.files.size)
        assertEquals(5, files.uploads.size)
        assertEquals(FileProblem.TooMany(5), model.uiState.value.files.notice)
        assertFalse(model.uiState.value.files.canAttach)
        model.explainFiles()
        assertEquals(FileProblem.TooMany(5), model.uiState.value.files.notice)
        model.hidden()
    }

    @Test fun removingAFileStopsItsUpload() = runTest(dispatcher) {
        val model = started()
        files.gate = CompletableDeferred()
        model.attach(listOf(picked("a.png"))); settle()
        val file = model.uiState.value.files.files.single()
        model.removeFile(file.localId); settle()
        assertTrue(model.uiState.value.files.files.isEmpty())
        files.gate!!.complete(Unit); settle()
        assertTrue(model.uiState.value.files.files.isEmpty())
        model.hidden()
    }

    @Test fun aPickerThatFailedSaysSo() = runTest(dispatcher) {
        val model = started()
        model.pickFailed()
        assertEquals(FileProblem.Unreadable, model.uiState.value.files.notice)
        model.hidden()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
