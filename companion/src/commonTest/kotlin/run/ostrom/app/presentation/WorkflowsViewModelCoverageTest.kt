// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import run.ostrom.app.account
import run.ostrom.app.domain.aWorkflow
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.Organization
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowEditRights
import run.ostrom.app.presentation.viewmodels.WorkflowsUiState
import run.ostrom.app.presentation.viewmodels.WorkflowsViewModel
import run.ostrom.app.presentation.viewmodels.userMessage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowsViewModelCoverageTest {
    private val h = WfcHarness()

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun model() = WorkflowsViewModel(h.accounts, h.loadWorkflows, h.loadRights, h.canManageRuns)

    @Test fun theStateTellsWhenToShowNewAndTheEmptyList() {
        assertFalse(WorkflowsUiState().canCreate, "No account")
        assertFalse(WorkflowsUiState(account = account(signedIn = false)).canCreate)
        assertTrue(WorkflowsUiState(account = account()).canCreate, "Rights not read: New shows")
        assertTrue(WorkflowsUiState(account = account(), rights = WorkflowEditRights(true, "all", 0)).canCreate)
        assertFalse(WorkflowsUiState(account = account(), rights = WorkflowEditRights(false, "admins", 0)).canCreate)
        assertFalse(WorkflowsUiState().empty, "Not read yet")
        assertTrue(WorkflowsUiState(workflows = emptyList()).empty)
        assertFalse(WorkflowsUiState(workflows = emptyList(), loading = true).empty)
        assertFalse(WorkflowsUiState(workflows = listOf(aWorkflow("w1", "One"))).empty)
    }

    @Test fun theListIsReadByNameWithTheRightsAndTheRole() = runTest {
        h.workflows.listAnswer = { listOf(aWorkflow("w2", "beta"), aWorkflow("w1", "Alpha")) }
        h.editor.rightsAnswer = { WorkflowEditRights(false, "admins", 0) }
        h.auth.organizations = listOf(Organization("o1", "Org", "member"))
        val model = model()
        assertTrue(h.workflows.listCalls.isEmpty(), "Nothing is read before the screen starts")
        model.start()
        val state = model.uiState.value
        assertEquals(listOf("Alpha", "beta"), state.workflows?.map { it.name })
        assertEquals(WorkflowEditRights(false, "admins", 0), state.rights)
        assertFalse(state.administers)
        assertFalse(state.loading)
        assertFalse(state.canCreate)
        h.auth.organizations = listOf(Organization("o1", "Org", "owner"))
        model.reload()
        assertTrue(model.uiState.value.administers)
        assertEquals(2, h.workflows.listCalls.size)
    }

    @Test fun rightsThatCannotBeReadLeaveNewShowing() = runTest {
        h.editor.rightsAnswer = { throw AppException.Unreachable() }
        val model = model()
        model.start()
        assertNull(model.uiState.value.rights)
        assertTrue(model.uiState.value.canCreate)
        assertEquals(emptyList(), model.uiState.value.workflows)
        assertTrue(model.uiState.value.empty)
    }

    @Test fun aFailedReadIsShownAndTheNextOneClearsIt() = runTest {
        h.workflows.listAnswer = { throw AppException.Unreachable() }
        val model = model()
        model.start()
        assertEquals(AppException.Unreachable().userMessage(), model.uiState.value.error)
        assertFalse(model.uiState.value.loading)
        assertNull(model.uiState.value.workflows)
        h.workflows.listAnswer = { listOf(aWorkflow("w1", "One")) }
        model.reload()
        assertNull(model.uiState.value.error)
        assertEquals(1, model.uiState.value.workflows?.size)
    }

    @Test fun nothingIsReadWithoutASignedInAccount() = runTest {
        h.accounts.update(account(signedIn = false))
        val model = model()
        model.start()
        model.reload()
        assertTrue(h.workflows.listCalls.isEmpty())
        assertFalse(model.uiState.value.loading)
        h.accounts.remove("a1")
        assertNull(model.uiState.value.account)
        model.reload()
        assertTrue(h.workflows.listCalls.isEmpty())
    }

    @Test fun theListFollowsTheActiveAccountAndItsSignIn() = runTest {
        h.accounts.remove("a1")
        val model = model()
        model.reload()
        assertNull(model.uiState.value.account, "No account at first")
        h.accounts.save(account())
        assertEquals("a1", model.uiState.value.account?.id)
        assertTrue(h.workflows.listCalls.isEmpty(), "Not started: nothing is read when the account changes")
        model.start()
        assertEquals(listOf("a1"), h.workflows.listCalls)
        // A renamed account is the same one: nothing is read again and the state is kept.
        h.accounts.update(account(login = "renamed"))
        assertEquals("ada", model.uiState.value.account?.login)
        assertEquals(1, h.workflows.listCalls.size)
        // Signed out: the same account, another state, which reads nothing.
        h.accounts.update(account(signedIn = false))
        assertEquals(false, model.uiState.value.account?.signedIn)
        assertNull(model.uiState.value.workflows)
        assertEquals(1, h.workflows.listCalls.size)
        h.accounts.update(account())
        assertEquals(2, h.workflows.listCalls.size)
        // Another account is read at once while started.
        h.accounts.save(account(id = "a2"))
        assertEquals(listOf("a1", "a1", "a2"), h.workflows.listCalls)
        h.accounts.remove("a2")
        h.accounts.remove("a1")
        assertNull(model.uiState.value.account)
    }

    @Test fun aReadThatLandsAfterTheAccountChangedIsDropped() = runTest {
        val gate = UncancellableGate<List<Workflow>>()
        h.workflows.listAnswer = { id -> if (id == "a1") gate.await() else emptyList() }
        h.editor.rightsAnswer = { id -> if (id == "a1") WorkflowEditRights(false, "admins", 0) else WorkflowEditRights(true, "all", 9) }
        val model = model()
        model.start()
        assertTrue(model.uiState.value.loading)
        // The person switches account while the first one is read.
        h.accounts.save(account(id = "a2"))
        assertEquals("a2", model.uiState.value.account?.id)
        assertEquals(emptyList(), model.uiState.value.workflows)
        assertEquals(9, model.uiState.value.rights?.memberCount)
        gate.release(listOf(aWorkflow("late", "Late")))
        assertEquals("a2", model.uiState.value.account?.id)
        assertEquals(emptyList(), model.uiState.value.workflows, "The late list of a1 is not shown under a2")
        assertEquals(9, model.uiState.value.rights?.memberCount, "Nor its rights")
        assertTrue(model.uiState.value.administers)
    }

    @Test fun aFailureThatLandsAfterTheAccountChangedIsDropped() = runTest {
        val gate = UncancellableGate<List<Workflow>>()
        h.workflows.listAnswer = { id -> if (id == "a1") gate.await() else emptyList() }
        val model = model()
        model.start()
        h.accounts.save(account(id = "a2"))
        gate.fail(AppException.Unreachable())
        assertNull(model.uiState.value.error)
        assertFalse(model.uiState.value.loading)
        assertEquals(emptyList(), model.uiState.value.workflows)
    }

    @Test fun rightsThatLandAfterTheAccountChangedAreDropped() = runTest {
        val gate = UncancellableGate<WorkflowEditRights>()
        h.editor.rightsAnswer = { id -> if (id == "a1") gate.await() else WorkflowEditRights(true, "all", 9) }
        val model = model()
        model.start()
        h.accounts.save(account(id = "a2"))
        assertEquals(9, model.uiState.value.rights?.memberCount)
        gate.release(WorkflowEditRights(false, "admins", 0))
        assertEquals("a2", model.uiState.value.account?.id)
        assertEquals(9, model.uiState.value.rights?.memberCount, "The late rights of a1 are not shown under a2")
    }

    @Test fun answersThatLandAfterTheAccountWasRemovedAreDropped() = runTest {
        // The rights land late.
        val rights = UncancellableGate<WorkflowEditRights>()
        h.editor.rightsAnswer = { rights.await() }
        val model = model()
        model.start()
        h.accounts.remove("a1")
        rights.release(WorkflowEditRights(false, "admins", 0))
        assertNull(model.uiState.value.account)
        assertNull(model.uiState.value.rights)
        // The list lands late, then another fails late.
        h.accounts.save(account())
        for (failure in listOf(false, true)) {
            val gate = UncancellableGate<List<Workflow>>()
            h.workflows.listAnswer = { gate.await() }
            h.editor.rightsAnswer = { WorkflowEditRights(true, "all", 1) }
            model.reload()
            h.accounts.remove("a1")
            if (failure) gate.fail(AppException.Unreachable()) else gate.release(listOf(aWorkflow("late", "Late")))
            assertNull(model.uiState.value.account)
            assertNull(model.uiState.value.error)
            assertNull(model.uiState.value.workflows)
            h.accounts.save(account())
        }
    }
}
