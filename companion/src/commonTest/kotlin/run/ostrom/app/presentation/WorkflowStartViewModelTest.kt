// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.FakeFeatures
import run.ostrom.app.SequenceIds
import run.ostrom.app.domain.aWorkflow
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Features
import run.ostrom.app.domain.models.RunStatus
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowInput
import run.ostrom.app.domain.models.WorkflowRun
import run.ostrom.app.domain.models.WorkflowRunPage
import run.ostrom.app.domain.repositories.WorkflowRepository
import run.ostrom.app.domain.usecases.workflows.GetWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.StartWorkflowRunUseCase
import run.ostrom.app.domain.usecases.workflows.WorkflowAccess
import run.ostrom.app.domain.workflowRun
import run.ostrom.app.fakeGuard
import run.ostrom.app.presentation.navigation.WorkflowStartRoute
import run.ostrom.app.presentation.viewmodels.WorkflowStartViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The form that starts a run keeps one idempotency key while it is unchanged, so a start repeated after a lost answer is the same press. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowStartViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Starts that fail while [failures] are left, with a lost connection. */
    private class Starts(var failures: Int) : WorkflowRepository {
        val keys = mutableListOf<String>()
        val release = aWorkflow("wf1", "Release").copy(inputs = listOf(WorkflowInput("version", "Version", required = true, default = null, maxLength = 10)))
        override suspend fun workflows(accountId: String): List<Workflow> = listOf(release)
        override suspend fun workflow(accountId: String, workflowId: String): Workflow = release
        override suspend fun runs(accountId: String, workflowId: String?, status: String?, before: String?) = WorkflowRunPage(emptyList(), null)
        override suspend fun run(accountId: String, runId: String): WorkflowRun = workflowRun(runId)
        override suspend fun start(accountId: String, workflowId: String, inputs: Map<String, String>, idempotencyKey: String): WorkflowRun {
            keys += idempotencyKey
            if (failures-- > 0) throw AppException.Unreachable()
            return workflowRun("new", RunStatus.QUEUED)
        }
        override suspend fun cancel(accountId: String, runId: String): WorkflowRun = workflowRun(runId, RunStatus.CANCELLED)
    }

    private fun model(starts: Starts): WorkflowStartViewModel {
        val access = WorkflowAccess(starts, FakeFeatures(setOf(Features.WORKFLOWS)), fakeGuard())
        return WorkflowStartViewModel(WorkflowStartRoute("a1", "wf1"), GetWorkflowUseCase(access), StartWorkflowRunUseCase(access), SequenceIds())
    }

    private fun TestScope.settle() = runCurrent()

    @Test fun aStartRepeatedAfterALostAnswerCarriesTheSameKeyAndEditingTheFormMakesAnotherPress() = runTest(dispatcher) {
        val starts = Starts(failures = 2)
        val model = model(starts); settle()
        model.onInput("version", "1.0"); settle()
        model.start(); settle()
        assertNotNull(model.uiState.value.startError)
        model.start(); settle()
        assertEquals(2, starts.keys.size)
        assertEquals(starts.keys[0], starts.keys[1], "The same press, so the server returns the first run")
        model.onInput("version", "1.1"); settle()
        model.start(); settle()
        assertEquals(3, starts.keys.size)
        assertEquals(3, starts.keys.distinct().size, "Another form is another press, so another key")
        assertNull(model.uiState.value.startError)
        assertEquals("new", model.uiState.value.started?.id)
    }
}
