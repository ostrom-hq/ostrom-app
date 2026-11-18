// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.ORIGIN
import run.ostrom.app.domain.aWorkflow
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.EditableWorkflow
import run.ostrom.app.domain.models.ProblemRule
import run.ostrom.app.domain.models.WorkflowEditRefusal
import run.ostrom.app.domain.models.WorkflowEditRights
import run.ostrom.app.domain.models.WorkflowProblem
import run.ostrom.app.domain.models.WorkflowWrite
import run.ostrom.app.domain.playedDraft
import run.ostrom.app.presentation.navigation.WorkflowEditRoute
import run.ostrom.app.presentation.viewmodels.HeldChange
import run.ostrom.app.presentation.viewmodels.WorkflowEditUiState
import run.ostrom.app.presentation.viewmodels.WorkflowEditViewModel
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
class WorkflowEditViewModelCoverageTest {
    private val h = WfcHarness()

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun model(workflowId: String? = null, accountId: String = "a1") =
        WorkflowEditViewModel(WorkflowEditRoute(accountId, workflowId), h.accounts, h.loadRights, h.loadEditable, h.loadWorkflows, h.saveWorkflow)

    /** A new workflow made valid: a name, and the one agent step titled and told what to do. */
    private fun WorkflowEditViewModel.complete() {
        edit { it.copy(name = "Release") }
        editNode("agent") { it.copy(title = "Write", instruction = "Write the notes") }
    }

    @Test fun saveNeedsALoadedAllowedWholeDraftAndNothingHeld() {
        val ready = WorkflowEditUiState(loading = false)
        assertTrue(ready.canSave)
        assertFalse(ready.copy(loading = true).canSave)
        assertFalse(ready.copy(loadError = AppException.Unreachable().userMessage()).canSave)
        assertFalse(ready.copy(notAllowed = true).canSave)
        assertFalse(ready.copy(saving = true).canSave)
        assertFalse(ready.copy(problems = listOf(WorkflowProblem(ProblemRule.NAME))).canSave)
        assertFalse(ready.copy(held = HeldChange(null, null)).canSave)
    }

    @Test fun theCalleesAreTheOtherWorkflowsAndTheProblemsAreFoundByNode() {
        assertNull(WorkflowEditUiState().callees, "The catalogue could not be read")
        val others = listOf(aWorkflow("wf1", "One"), aWorkflow("wf2", "Two"))
        assertEquals(others, WorkflowEditUiState(others = others).callees, "A new workflow may call any")
        val editing = EditableWorkflow("wf1", 1, playedDraft())
        assertEquals(listOf("wf2"), WorkflowEditUiState(others = others, editing = editing).callees?.map { it.id })
        val problems = listOf(WorkflowProblem(ProblemRule.NAME), WorkflowProblem(ProblemRule.TITLE, "write"))
        assertEquals(listOf(problems[0]), WorkflowEditUiState(problems = problems).problemsOf(null))
        assertEquals(listOf(problems[1]), WorkflowEditUiState(problems = problems).problemsOf("write"))
    }

    @Test fun aNewWorkflowStartsWithOneStepAndSavesOnceItIsDescribed() = runTest {
        h.workflows.listAnswer = { listOf(aWorkflow("o1", "Other")) }
        val model = model()
        assertTrue(model.uiState.value.creating)
        assertFalse(model.uiState.value.loading)
        assertNull(model.uiState.value.editing)
        assertEquals(listOf("Other"), model.uiState.value.others?.map { it.name })
        assertFalse(model.uiState.value.canSave, "A name and instructions are missing")
        model.save()
        assertTrue(h.editor.calls.isEmpty())
        model.complete()
        assertTrue(model.uiState.value.problems.isEmpty())
        assertEquals("Write", model.uiState.value.draft.nodes.single().title)
        assertTrue(model.uiState.value.canSave)
        model.save()
        assertEquals(listOf("create Release"), h.editor.calls)
        assertEquals("wf9", model.uiState.value.savedId)
        assertFalse(model.uiState.value.saving)
    }

    @Test fun editingANodeChangesOnlyThatNode() = runTest {
        val model = model("wf1")
        val before = model.uiState.value.draft.nodes
        model.editNode("ask") { it.copy(title = "Approve it") }
        val nodes = model.uiState.value.draft.nodes
        assertEquals(before[0], nodes[0])
        assertEquals("Approve it", nodes[1].title)
        model.editNode("nowhere") { it.copy(title = "Never") }
        assertEquals(nodes, model.uiState.value.draft.nodes)
    }

    @Test fun aSavedWorkflowIsReadWholeAndSavedOverTheVersionRead() = runTest {
        val model = model("wf1")
        assertFalse(model.uiState.value.creating)
        assertEquals("wf1", model.uiState.value.editing?.id)
        assertEquals(playedDraft(), model.uiState.value.draft)
        assertTrue(model.uiState.value.canSave)
        model.save()
        assertEquals(listOf("get wf1", "save wf1 v3 Release"), h.editor.calls)
        assertEquals("wf1", model.uiState.value.savedId)
    }

    @Test fun anUnreadableRightsAndCatalogueLeaveTheFormOpenAndNotAllowedClosesIt() = runTest {
        h.editor.rightsAnswer = { throw AppException.Unreachable() }
        h.workflows.listAnswer = { throw AppException.Unreachable() }
        val open = model("wf1")
        assertFalse(open.uiState.value.notAllowed)
        assertNull(open.uiState.value.others)
        assertNull(open.uiState.value.callees)
        assertTrue(open.uiState.value.canSave)
        h.editor.rightsAnswer = { WorkflowEditRights(true, "all", 0) }
        assertFalse(model("wf1").uiState.value.notAllowed)
        h.editor.rightsAnswer = { WorkflowEditRights(false, "admins", 0) }
        val closed = model("wf1")
        assertTrue(closed.uiState.value.notAllowed)
        assertFalse(closed.uiState.value.canSave)
        closed.save()
        assertEquals(listOf("get wf1", "get wf1", "get wf1"), h.editor.calls)
    }

    @Test fun aWorkflowThatCannotBeReadIsAnErrorUntilItIsReadAgain() = runTest {
        h.editor.editableAnswer = { throw AppException.NotFound() }
        val model = model("wf1")
        assertEquals(AppException.NotFound().userMessage(), model.uiState.value.loadError)
        assertFalse(model.uiState.value.loading)
        assertFalse(model.uiState.value.canSave)
        h.editor.editableAnswer = { id -> EditableWorkflow(id, 4, playedDraft()) }
        model.load()
        assertNull(model.uiState.value.loadError)
        assertEquals(4, model.uiState.value.editing?.version)
        assertTrue(model.uiState.value.canSave)
    }

    @Test fun aHeldSaveOffersItsConsolePageOnlyOnTheAccountsServer() = runTest {
        h.editor.saveAnswer = { _, _ -> WorkflowWrite.Held("c1", "$ORIGIN/confirm/c1", 99L) }
        val model = model("wf1")
        model.save()
        assertEquals(HeldChange("$ORIGIN/confirm/c1", 99L), model.uiState.value.held)
        assertNull(model.uiState.value.savedId)
        assertFalse(model.uiState.value.canSave)
        // An account this device does not know has no server to open the page on.
        h.editor.createAnswer = { WorkflowWrite.Held("c2", "$ORIGIN/confirm/c2", null) }
        val unknown = model(accountId = "zz")
        unknown.complete()
        unknown.save()
        assertEquals(HeldChange(null, null), unknown.uiState.value.held)
    }

    @Test fun aSaveTheServerRefusesIsShownAndAStaleOneOffersTheNewerVersion() = runTest {
        val model = model("wf1")
        h.editor.saveAnswer = { _, _ -> throw AppException.WorkflowEditRefused(WorkflowEditRefusal.NAME_TAKEN) }
        model.save()
        assertEquals(AppException.WorkflowEditRefused(WorkflowEditRefusal.NAME_TAKEN).userMessage(), model.uiState.value.error)
        assertFalse(model.uiState.value.conflict)
        assertFalse(model.uiState.value.saving)
        h.editor.saveAnswer = { _, _ -> throw AppException.WorkflowEditRefused(WorkflowEditRefusal.STALE) }
        model.save()
        assertTrue(model.uiState.value.conflict)
        assertNotNull(model.uiState.value.error)
        h.editor.saveAnswer = { _, _ -> throw AppException.Unreachable() }
        model.save()
        assertFalse(model.uiState.value.conflict, "Cleared by the next save")
        h.editor.saveAnswer = { _, _ -> throw AppException.WorkflowEditRefused(WorkflowEditRefusal.STALE) }
        model.save()
        assertTrue(model.uiState.value.conflict)
        h.editor.editableAnswer = { id -> EditableWorkflow(id, 5, playedDraft()) }
        model.reloadAfterConflict()
        assertFalse(model.uiState.value.conflict)
        assertNull(model.uiState.value.error)
        assertEquals(5, model.uiState.value.editing?.version)
    }

    @Test fun aDraftTheServerFindsInvalidShowsItsProblemsAgainstTheirNodes() = runTest {
        val problems = listOf(WorkflowProblem(ProblemRule.TITLE, "write", listOf("200")))
        h.editor.saveAnswer = { _, _ -> throw AppException.WorkflowInvalid(problems) }
        val model = model("wf1")
        model.save()
        assertEquals(problems, model.uiState.value.problems)
        assertEquals(problems, model.uiState.value.problemsOf("write"))
        assertNotNull(model.uiState.value.error)
        assertFalse(model.uiState.value.saving)
        assertFalse(model.uiState.value.canSave)
    }
}
