// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeFeatures
import run.ostrom.app.SequenceIds
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.FilePolicy
import run.ostrom.app.domain.models.FileProblem
import run.ostrom.app.domain.models.PendingFile
import run.ostrom.app.domain.models.PickedFile
import run.ostrom.app.domain.models.UploadedFile
import run.ostrom.app.domain.repositories.SessionFileRepository
import run.ostrom.app.domain.usecases.files.SessionFilesUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.picked
import run.ostrom.app.policy as policyOf
import run.ostrom.app.presentation.viewmodels.FilesUiState
import run.ostrom.app.presentation.viewmodels.SessionFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A file repository whose policy read and uploads a test scripts. */
private class CoverageFiles : SessionFileRepository {
    var filePolicy: FilePolicy = policyOf()
    var policyHold: suspend () -> Unit = {}
    var policyFailure: Exception? = null
    var policyReads = 0
    val uploads = mutableListOf<String>()
    var script: suspend (PickedFile, String, (Long, Long) -> Unit) -> UploadedFile = { file, key, progress ->
        progress(file.size, file.size)
        UploadedFile("f-$key", file.name, file.size, "image/png")
    }

    override suspend fun policy(accountId: String): FilePolicy {
        policyReads++
        policyHold()
        policyFailure?.let { throw it }
        return filePolicy
    }

    override suspend fun upload(
        accountId: String, sessionId: String, file: PickedFile, idempotencyKey: String, onProgress: (sent: Long, total: Long) -> Unit,
    ): UploadedFile {
        uploads += file.name
        return script(file, idempotencyKey, onProgress)
    }

    override fun typeFor(name: String): String? = if (name.endsWith(".png")) "image/png" else null
}

/** The composer's files at their edges, with the pending files held by the test. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionFilesCoverageTest {
    private val repository = CoverageFiles()
    private val features = FakeFeatures(setOf(Features.RUNNERS_CLOUD_FILES))
    private var state = FilesUiState()
    private var writes = 0

    private fun files(scope: CoroutineScope) =
        SessionFiles(
            scope, "a1", "s1", SessionFilesUseCase(repository, features, fakeGuard(), SequenceIds()),
            read = { state }, write = { change -> writes++; state = change(state) },
        )

    private fun pending(id: String, file: PendingFile.State, problem: FileProblem? = null) = PendingFile(id, "$id.png", 100, file, problem = problem)

    @Test
    fun theStateAnswersForEveryPolicyAndFile() {
        val uploaded = pending("l1", PendingFile.State.UPLOADED)
        assertFalse(FilesUiState().canAttach, "no policy, no paperclip")
        assertFalse(FilesUiState(policy = policyOf(available = false)).canAttach)
        assertFalse(FilesUiState(policy = policyOf(maxFiles = 1), files = listOf(uploaded)).canAttach)
        assertTrue(FilesUiState(policy = policyOf(maxFiles = 2), files = listOf(uploaded)).canAttach)
        assertTrue(FilesUiState(files = listOf(uploaded, uploaded.copy(state = PendingFile.State.UPLOADING))).uploading)
        assertFalse(FilesUiState(files = listOf(uploaded, uploaded.copy(state = PendingFile.State.FAILED))).uploading)
        assertFalse(FilesUiState().uploading)
    }

    @Test
    fun thePolicyIsReadOnceAtATimeAndAgainAfterAFailedRead() = runTest {
        val files = files(this)
        val gate = CompletableDeferred<Unit>()
        repository.policyHold = { gate.await() }
        files.load(); files.load(); runCurrent()
        assertEquals(1, repository.policyReads, "a read on its way is not doubled")
        gate.complete(Unit); runCurrent()
        assertNotNull(state.policy)
        files.load(); runCurrent()
        assertEquals(1, repository.policyReads, "the policy is already there")

        state = FilesUiState()
        repository.policyFailure = AppException.Unreachable()
        files.load(); runCurrent()
        assertEquals(2, repository.policyReads)
        assertNull(state.policy)
        repository.policyFailure = null
        files.load(); runCurrent()
        assertEquals(3, repository.policyReads, "a failed read is made again")
        assertNotNull(state.policy)
    }

    @Test
    fun thePaperclipExplainsOnlyWhatIsWrong() = runTest {
        val files = files(this)
        files.explainUnavailable()
        assertNull(state.notice, "no policy yet")
        state = FilesUiState(policy = policyOf())
        files.explainUnavailable()
        assertNull(state.notice, "there is room")
        state = FilesUiState(policy = policyOf(maxFiles = 1), files = listOf(pending("l1", PendingFile.State.UPLOADED)))
        files.explainUnavailable()
        assertEquals(FileProblem.TooMany(1), state.notice)
        state = FilesUiState(policy = policyOf(available = false))
        files.explainUnavailable()
        assertEquals(FileProblem.StorageNotConfigured, state.notice)
    }

    @Test
    fun onlyAFailedFileWithARetryableProblemAndASourceIsUploadedAgain() = runTest {
        val files = files(this)
        files.retry("l1")
        assertTrue(repository.uploads.isEmpty(), "no policy yet")
        state = FilesUiState(
            policy = policyOf(),
            files = listOf(
                pending("done", PendingFile.State.UPLOADED),
                pending("unsaid", PendingFile.State.FAILED),
                pending("final", PendingFile.State.FAILED, FileProblem.Empty),
                pending("lost", PendingFile.State.FAILED, FileProblem.Unreachable),
            ),
        )
        val before = state
        listOf("nobody", "done", "unsaid", "final", "lost").forEach { files.retry(it) }
        runCurrent()
        assertTrue(repository.uploads.isEmpty())
        assertEquals(before, state)
    }

    @Test
    fun theUploadedFilesAreHandedOverOnlyOnceEveryFileIsUploaded() = runTest {
        val files = files(this)
        state = FilesUiState(files = listOf(pending("a", PendingFile.State.UPLOADING)))
        assertNull(files.uploaded())
        state = FilesUiState()
        assertEquals(emptyList<UploadedFile>(), files.uploaded())
        state = FilesUiState(files = listOf(pending("a", PendingFile.State.UPLOADED).copy(uploaded = UploadedFile("f1", "a.png", 100, "image/png"))))
        assertEquals(listOf("f1"), files.uploaded()?.map { it.id })
    }

    @Test
    fun progressIsShownOncePerPercentEvenWithoutATotal() = runTest {
        val files = files(this)
        state = FilesUiState(policy = policyOf())
        val gate = CompletableDeferred<Unit>()
        repository.script = { file, key, progress ->
            progress(0, 0)
            progress(0, 0)
            gate.await()
            progress(file.size, file.size)
            UploadedFile("f-$key", file.name, file.size, "image/png")
        }
        files.attach(listOf(picked("a.png")))
        val before = writes
        runCurrent()
        assertEquals(1, writes - before, "the same percent twice changes the state once")
        assertEquals(PendingFile.State.UPLOADING, state.files.single().state)
        assertEquals(0L, state.files.single().sent)
        gate.complete(Unit); runCurrent()
        assertEquals(PendingFile.State.UPLOADED, state.files.single().state)
        assertEquals(100L, state.files.single().sent)
    }
}
