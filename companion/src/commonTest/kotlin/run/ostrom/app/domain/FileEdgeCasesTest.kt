// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.domain

import kotlinx.coroutines.test.runTest
import run.ostrom.app.FakeFeatures
import run.ostrom.app.FakeFileRepository
import run.ostrom.app.SequenceIds
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.FileProblem
import run.ostrom.app.domain.models.PendingFile
import run.ostrom.app.domain.models.UploadOutcome
import run.ostrom.app.domain.usecases.files.PhotoSizing
import run.ostrom.app.domain.usecases.files.SessionFilesUseCase
import run.ostrom.app.fakeGuard
import run.ostrom.app.picked
import run.ostrom.app.policy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FileEdgeCasesTest {
    private val repository = FakeFileRepository()
    private val files = SessionFilesUseCase(repository, FakeFeatures(setOf(Features.RUNNERS_CLOUD_CONVERSATION, Features.RUNNERS_CLOUD_FILES)), fakeGuard(), SequenceIds())

    @Test
    fun thePickersTypeIsReadWithoutItsParametersAndInLowerCase() {
        val team = policy(types = listOf("image/png", "text/csv"))
        assertEquals("text/csv", files.typeOf(picked("export", type = " TEXT/CSV ; charset=utf-8"), team))
        assertNull(files.typeOf(picked("blob", type = " ; x=y"), team))
        assertNull(files.typeOf(picked("blob", type = null), team))
        // A text type the team does not take stays as it is when the team takes no plain text either.
        assertEquals("text/x-log", files.typeOf(picked("build", type = "text/x-log"), team))
        assertEquals("application/zip", files.typeOf(picked("archive", type = "application/zip"), policy()))
    }

    @Test
    fun eachFailureOfAnUploadBecomesItsProblem() = runTest {
        suspend fun outcome(error: Exception): FileProblem {
            repository.result = { _, _ -> throw error }
            return (files.upload("a1", "s1", picked("a.png"), policy(), "k") { _, _ -> } as UploadOutcome.Failed).problem
        }
        assertEquals(FileProblem.Unsupported, outcome(AppException.Unsupported(Features.RUNNERS_CLOUD_FILES)))
        assertEquals(FileProblem.Other("rate_limited", transient = true), outcome(AppException.RateLimited(10)))
        assertEquals(FileProblem.Other("timeout", transient = true), outcome(AppException.Server(408, "timeout")))
        assertEquals(FileProblem.Other("bad", transient = false), outcome(AppException.Server(400, "bad")))
        assertEquals(FileProblem.Other(null, transient = false), outcome(IllegalStateException("broken")))
    }

    @Test
    fun eachRefusalOfTheServerNamesItsProblem() {
        val team = policy()
        fun refusal(code: String?, status: Int) = SessionFilesUseCase.refusal(AppException.FileRefused(code, status), team)
        assertEquals(FileProblem.Empty, refusal("empty_file", 400))
        assertEquals(FileProblem.Denied, refusal("forbidden", 403))
        assertEquals(FileProblem.Denied, refusal("other", 403))
        assertEquals(FileProblem.Other("busy", transient = true), refusal("busy", 503))
        assertEquals(FileProblem.Other(null, transient = true), refusal(null, 408))
        assertEquals(FileProblem.Other("slow_down", transient = true), refusal("slow_down", 429))
        assertEquals(FileProblem.Other("nope", transient = false), refusal("nope", 422))
    }

    @Test
    fun onlySomeProblemsCanBeRetried() {
        assertFalse(FileProblem.Unsupported.retryable)
        assertFalse(FileProblem.Empty.retryable)
        assertFalse(FileProblem.SessionGone.retryable)
    }

    @Test
    fun anEmptyFileShowsNoProgressAndProgressStaysWithinBounds() {
        assertEquals(0f, PendingFile("l1", "a.png", 0, PendingFile.State.UPLOADING, sent = 0).progress)
        assertEquals(0.5f, PendingFile("l1", "a.png", 100, PendingFile.State.UPLOADING, sent = 50).progress)
        assertEquals(1f, PendingFile("l1", "a.png", 100, PendingFile.State.UPLOADING, sent = 150).progress)
    }

    @Test
    fun aPhotoMustHaveAPositiveSize() {
        assertFailsWith<IllegalArgumentException> { PhotoSizing.target(0, 10) }
        assertFailsWith<IllegalArgumentException> { PhotoSizing.target(10, 0) }
        assertFailsWith<IllegalArgumentException> { PhotoSizing.target(10, 10, max = 0) }
        assertEquals(2048 to 2048, PhotoSizing.target(2048, 2048))
    }

    @Test
    fun aReducedPhotoKeepsItsNameWithoutFoldersOrExtension() {
        assertEquals("IMG_0002.jpg", PhotoSizing.jpegName("DCIM/Camera/IMG_0002.heic"))
        assertEquals("notes.jpg", PhotoSizing.jpegName("notes"))
        assertEquals("photo.jpg", PhotoSizing.jpegName(" .png"))
        assertEquals("photo.jpg", PhotoSizing.jpegName("folder/"))
    }
}
