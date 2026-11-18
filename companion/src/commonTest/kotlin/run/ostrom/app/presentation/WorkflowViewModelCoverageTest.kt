// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.ORIGIN
import run.ostrom.app.SequenceIds
import run.ostrom.app.domain.aWorkflow
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.NodeKind
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowEditRights
import run.ostrom.app.domain.models.WorkflowInput
import run.ostrom.app.domain.models.WorkflowNode
import run.ostrom.app.domain.models.WorkflowWrite
import run.ostrom.app.domain.usecases.workflows.StartWorkflowRunUseCase
import run.ostrom.app.domain.workflowRun
import run.ostrom.app.presentation.navigation.WorkflowRoute
import run.ostrom.app.presentation.navigation.WorkflowStartRoute
import run.ostrom.app.presentation.viewmodels.HeldChange
import run.ostrom.app.presentation.viewmodels.WorkflowStartUiState
import run.ostrom.app.presentation.viewmodels.WorkflowStartViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowUiState
import run.ostrom.app.presentation.viewmodels.WorkflowViewModel
import run.ostrom.app.presentation.viewmodels.userMessage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowViewModelCoverageTest {
    private val h = WfcHarness()
    private val one = aWorkflow("w1", "Release").copy(nodes = listOf(WorkflowNode("a", NodeKind.AGENT, "A", null)))

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun model(accountId: String = "a1") =
        WorkflowViewModel(WorkflowRoute(accountId, "w1"), h.getWorkflow, h.canManageRuns, h.loadRights, h.deleteWorkflow, h.accounts)

    @Test fun theStateTellsWhichActionsShow() {
        val unknown = one.copy(nodes = one.nodes + WorkflowNode("u", NodeKind.UNKNOWN, "U", null))
        assertTrue(WorkflowUiState(mayDecide = true, workflow = one).canEdit)
        assertFalse(WorkflowUiState(mayDecide = false, workflow = one).canEdit)
        assertFalse(WorkflowUiState(mayDecide = true).canEdit, "Not read yet")
        assertFalse(WorkflowUiState(mayDecide = true, workflow = unknown).canEdit, "A kind this app does not know is not edited")
        val deletable = WorkflowUiState(mayDecide = true, workflow = one)
        assertTrue(deletable.canDelete)
        assertFalse(deletable.copy(mayDecide = false).canDelete)
        assertFalse(deletable.copy(workflow = null).canDelete)
        assertFalse(deletable.copy(deleting = true).canDelete)
        assertFalse(deletable.copy(deleteHeld = HeldChange(null, null)).canDelete)
        assertFalse(deletable.copy(deleted = true).canDelete)
        assertTrue(WorkflowUiState(managesRuns = true, workflow = one).canStart)
        assertFalse(WorkflowUiState(managesRuns = false, workflow = one).canStart)
        assertFalse(WorkflowUiState(managesRuns = true).canStart)
        assertFalse(WorkflowUiState(managesRuns = true, workflow = one.copy(status = "proposed")).canStart)
    }

    @Test fun aRunnableWorkflowIsReadWithTheRoleAndTheRights() = runTest {
        h.workflows.oneAnswer = { _, _ -> one }
        val model = model()
        val state = model.uiState.value
        assertEquals(one, state.workflow)
        assertFalse(state.loading)
        assertTrue(state.managesRuns)
        assertTrue(state.mayDecide)
        assertTrue(state.canEdit)
    }

    @Test fun theRoleOfAMemberAndAWorkflowThatDoesNotRunHideStart() = runTest {
        h.workflows.oneAnswer = { _, _ -> one }
        h.auth.organizations = listOf(Organization("o1", "Org", "member"))
        assertFalse(model().uiState.value.managesRuns)
        h.auth.organizations = listOf(Organization("o1", "Org", "owner"))
        h.workflows.oneAnswer = { _, _ -> one.copy(status = "rejected") }
        val rejected = model().uiState.value
        assertFalse(rejected.managesRuns, "The role is read only for a workflow that runs")
        assertFalse(rejected.canStart)
    }

    @Test fun rightsThatCannotBeReadLetThePersonDecide() = runTest {
        h.workflows.oneAnswer = { _, _ -> one }
        h.editor.rightsAnswer = { throw AppException.Unreachable() }
        assertTrue(model().uiState.value.mayDecide)
        h.editor.rightsAnswer = { WorkflowEditRights(false, "admins", 0) }
        val refused = model().uiState.value
        assertFalse(refused.mayDecide)
        assertFalse(refused.canEdit)
    }

    @Test fun aWorkflowGoneAndAFailedReadAreShownAndTheNextReadClearsThem() = runTest {
        h.workflows.oneAnswer = { _, _ -> throw AppException.NotFound() }
        val model = model()
        assertTrue(model.uiState.value.gone)
        assertEquals(AppException.NotFound().userMessage(), model.uiState.value.error)
        h.workflows.oneAnswer = { _, _ -> throw AppException.Unreachable() }
        model.load()
        assertEquals(AppException.Unreachable().userMessage(), model.uiState.value.error)
        assertFalse(model.uiState.value.loading)
        h.workflows.oneAnswer = { _, _ -> one }
        model.load()
        assertNull(model.uiState.value.error)
        assertFalse(model.uiState.value.gone, "Found again")
        assertEquals(one, model.uiState.value.workflow)
    }

    @Test fun deletingWaitsForTheWorkflowAndHappensOnce() = runTest {
        val gate = CompletableDeferred<Workflow>()
        h.workflows.oneAnswer = { _, _ -> gate.await() }
        val model = model()
        model.delete()
        assertTrue(h.editor.calls.isEmpty(), "Nothing to delete before the workflow is read")
        gate.complete(one)
        model.delete()
        assertEquals(listOf("delete w1"), h.editor.calls)
        assertTrue(model.uiState.value.deleted)
        assertFalse(model.uiState.value.deleting)
        model.delete()
        assertEquals(1, h.editor.calls.size, "A deleted workflow is not deleted again")
    }

    @Test fun aHeldDeletionOffersItsConsolePageOnlyOnTheAccountsServer() = runTest {
        h.workflows.oneAnswer = { _, _ -> one }
        h.editor.deleteAnswer = { WorkflowWrite.Held("c1", "$ORIGIN/confirm/c1", 99L) }
        val model = model()
        model.delete()
        assertEquals(HeldChange("$ORIGIN/confirm/c1", 99L), model.uiState.value.deleteHeld)
        assertFalse(model.uiState.value.canDelete)
        assertFalse(model.uiState.value.deleted)
        h.editor.deleteAnswer = { WorkflowWrite.Held("c2", "https://evil.example/confirm/c2", null) }
        val foreign = model()
        foreign.delete()
        assertEquals(HeldChange(null, null), foreign.uiState.value.deleteHeld)
        // An account this device does not know has no server to open the page on.
        h.editor.deleteAnswer = { WorkflowWrite.Held("c3", "$ORIGIN/confirm/c3", 5L) }
        val unknown = model(accountId = "zz")
        unknown.delete()
        assertEquals(HeldChange(null, 5L), unknown.uiState.value.deleteHeld)
    }

    @Test fun aFailedDeletionIsShownAndCanBeRetried() = runTest {
        h.workflows.oneAnswer = { _, _ -> one }
        h.editor.deleteAnswer = { throw AppException.Unreachable() }
        val model = model()
        model.delete()
        assertEquals(AppException.Unreachable().userMessage(), model.uiState.value.deleteError)
        assertFalse(model.uiState.value.deleting)
        assertTrue(model.uiState.value.canDelete)
        h.editor.deleteAnswer = { WorkflowWrite.Applied(Unit) }
        model.delete()
        assertNull(model.uiState.value.deleteError)
        assertTrue(model.uiState.value.deleted)
    }

    // The form that starts a run.

    private val release = aWorkflow("w1", "Release").copy(
        inputs = listOf(
            WorkflowInput("version", "Version", required = true, default = null, maxLength = 3),
            WorkflowInput("notes", "Notes", required = false, default = "none"),
        ),
    )

    private fun form() = WorkflowStartViewModel(WorkflowStartRoute("a1", "w1"), h.getWorkflow, StartWorkflowRunUseCase(h.access), SequenceIds())

    @Test fun theStartStateReadsTypedValuesThenDefaults() {
        val state = WorkflowStartUiState(workflow = release)
        assertEquals("none", state.valueOf("notes"))
        assertEquals("", state.valueOf("version"))
        assertEquals("", state.valueOf("unknown"))
        assertEquals("", WorkflowStartUiState().valueOf("version"))
        assertEquals("typed", state.copy(inputs = mapOf("notes" to "typed")).valueOf("notes"))
        assertFalse(WorkflowStartUiState().filled, "No workflow")
        assertFalse(state.filled, "A required input has no value")
        assertFalse(state.copy(inputs = mapOf("version" to "  ")).filled, "Blank is no value")
        assertTrue(state.copy(inputs = mapOf("version" to "1")).filled)
        assertTrue(WorkflowStartUiState(workflow = aWorkflow("w2", "None")).filled, "No input to fill")
        val ready = state.copy(inputs = mapOf("version" to "1"))
        assertTrue(ready.canStart)
        assertFalse(WorkflowStartUiState().canStart)
        assertFalse(state.canStart)
        assertFalse(ready.copy(starting = true).canStart)
        assertFalse(ready.copy(started = workflowRun("r1")).canStart)
    }

    @Test fun theFormIgnoresInputAndStartUntilTheWorkflowIsRead() = runTest {
        val gate = CompletableDeferred<Workflow>()
        h.workflows.oneAnswer = { _, _ -> gate.await() }
        val form = form()
        form.onInput("version", "1")
        form.start()
        assertTrue(form.uiState.value.inputs.isEmpty())
        assertTrue(h.workflows.startKeys.isEmpty())
        assertTrue(form.uiState.value.loading)
        gate.complete(release)
        assertFalse(form.uiState.value.loading)
    }

    @Test fun aWorkflowGoneAndAFailedReadOfTheFormAreShown() = runTest {
        h.workflows.oneAnswer = { _, _ -> throw AppException.NotFound() }
        val form = form()
        assertTrue(form.uiState.value.gone)
        assertEquals(AppException.NotFound().userMessage(), form.uiState.value.error)
        h.workflows.oneAnswer = { _, _ -> throw IllegalStateException("boom") }
        form.load()
        assertEquals(IllegalStateException("boom").userMessage(), form.uiState.value.error)
        assertFalse(form.uiState.value.loading)
        h.workflows.oneAnswer = { _, _ -> release }
        form.load()
        assertNull(form.uiState.value.error)
        assertFalse(form.uiState.value.gone)
        assertEquals(release, form.uiState.value.workflow)
    }

    @Test fun anInputIsKeptToItsMaximumAndOnlyAChangeMakesAnotherPress() = runTest {
        h.workflows.oneAnswer = { _, _ -> release }
        var sent: Map<String, String>? = null
        h.workflows.startAnswer = { _, inputs, _ -> sent = inputs; throw AppException.Unreachable() }
        val form = form()
        form.start()
        assertTrue(h.workflows.startKeys.isEmpty(), "A required input has no value")
        form.onInput("nope", "x")
        assertTrue(form.uiState.value.inputs.isEmpty(), "An input the workflow does not have is ignored")
        form.onInput("version", "abcdef")
        assertEquals("abc", form.uiState.value.inputs["version"])
        form.start()
        assertEquals(mapOf("version" to "abc", "notes" to "none"), sent)
        assertNotNull(form.uiState.value.startError)
        form.onInput("version", "abcxyz")
        assertNull(form.uiState.value.startError, "Typing clears the failure")
        form.start()
        assertEquals(2, h.workflows.startKeys.size)
        assertEquals(h.workflows.startKeys[0], h.workflows.startKeys[1], "The same form is the same press")
        h.workflows.startAnswer = { _, _, _ -> workflowRun("new") }
        form.onInput("version", "abd")
        form.start()
        assertEquals(3, h.workflows.startKeys.size)
        assertTrue(h.workflows.startKeys[2] != h.workflows.startKeys[1])
        assertEquals("new", form.uiState.value.started?.id)
        assertFalse(form.uiState.value.starting)
        form.start()
        assertEquals(3, h.workflows.startKeys.size, "A started form does not start again")
    }

    @Test fun aStartThatFailsWithoutAnExplanationIsWordedGenerically() = runTest {
        h.workflows.oneAnswer = { _, _ -> release }
        h.workflows.startAnswer = { _, _, _ -> throw IllegalStateException("boom") }
        val form = form()
        form.onInput("version", "1")
        form.start()
        assertEquals("Something went wrong.", form.uiState.value.startError?.text)
        assertNull(form.uiState.value.started)
    }
}
