// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), companion/.../presentation/viewmodels/WorkflowsViewModels.kt; see NOTICE.
package run.ostrom.app.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import run.ostrom.app.domain.models.AppException
import run.ostrom.app.domain.models.DraftNode
import run.ostrom.app.domain.models.EditableWorkflow
import run.ostrom.app.domain.models.Workflow
import run.ostrom.app.domain.models.WorkflowDraft
import run.ostrom.app.domain.models.WorkflowEditRefusal
import run.ostrom.app.domain.models.WorkflowProblem
import run.ostrom.app.domain.models.WorkflowWrite
import run.ostrom.app.domain.repositories.AccountRepository
import run.ostrom.app.domain.repositories.find
import run.ostrom.app.domain.usecases.workflows.LoadEditableWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowEditRightsUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowsUseCase
import run.ostrom.app.domain.usecases.workflows.SaveWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.WorkflowChecks
import run.ostrom.app.domain.usecases.workflows.WorkflowEdits
import run.ostrom.app.domain.usecases.workflows.confirmationLink
import run.ostrom.app.presentation.navigation.WorkflowEditRoute

/**
 * A change that waits for an owner's or an admin's confirmation on the console; [link] is its page when it opens on
 * the account's own server, null otherwise (the change is then confirmed from the console by hand).
 */
data class HeldChange(val link: String?, val expiresAt: Long?)

data class WorkflowEditUiState(
    /** A new workflow, not one read from the server. */
    val creating: Boolean = true,
    val loading: Boolean = true,
    val loadError: Failure? = null,
    /** The person may not decide workflows (the settings say so): the form does not show. */
    val notAllowed: Boolean = false,
    /** The workflow changed, null while creating one. */
    val editing: EditableWorkflow? = null,
    val draft: WorkflowDraft = WorkflowEdits.newDraft(),
    /** The organization's workflows, for the callee picker and the call checks; null when unreadable. */
    val others: List<Workflow>? = null,
    val problems: List<WorkflowProblem> = emptyList(),
    val saving: Boolean = false,
    val error: Failure? = null,
    /** The save was refused over a newer version: offer to reload it. */
    val conflict: Boolean = false,
    val held: HeldChange? = null,
    /** The id the workflow was saved under: the screen moves to it. */
    val savedId: String? = null,
) {
    fun problemsOf(nodeId: String?): List<WorkflowProblem> = problems.filter { it.nodeId == nodeId }

    val canSave: Boolean get() = !loading && loadError == null && !notAllowed && !saving && problems.isEmpty() && held == null

    /** The workflows a `workflow` node may call: every other one. */
    val callees: List<Workflow>? get() = others?.filter { it.id != editing?.id }
}

/**
 * The workflow editor: a new workflow ([WorkflowEditRoute.workflowId] null) or a saved one read whole. Every rule the
 * server checks is checked as the person types and shown against its node; Save stays off while any is left. A save
 * over a newer version offers to reload it; a save held for a confirmation says so and offers its console page.
 */
class WorkflowEditViewModel(
    private val route: WorkflowEditRoute,
    private val accounts: AccountRepository,
    private val loadRights: LoadWorkflowEditRightsUseCase,
    private val loadEditable: LoadEditableWorkflowUseCase,
    private val loadWorkflows: LoadWorkflowsUseCase,
    private val saveWorkflow: SaveWorkflowUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(WorkflowEditUiState(creating = route.workflowId == null))
    val uiState: StateFlow<WorkflowEditUiState> = state.asStateFlow()
    private var loading: Job? = null

    init { load() }

    fun load() {
        loading?.cancel()
        state.update { it.copy(loading = true, loadError = null, conflict = false, error = null) }
        loading = viewModelScope.launch {
            // The rights and the catalogue are read alongside; when unreadable the form shows and the server's refusal is worded.
            val rights = async { optional { loadRights(route.accountId) } }
            val others = async { optional { loadWorkflows(route.accountId) } }
            try {
                val editing = route.workflowId?.let { loadEditable(route.accountId, it) }
                val draft = if (editing != null) editing.draft else state.value.draft
                val catalogue = others.await()
                state.update {
                    it.copy(
                        loading = false, editing = editing, draft = draft, others = catalogue, notAllowed = rights.await()?.mayDecide == false,
                        problems = WorkflowChecks.check(draft, catalogue, editing?.id),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, loadError = error.userMessage()) }
            }
        }
    }

    private suspend fun <T> optional(read: suspend () -> T): T? = try {
        read()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    /** Changes the draft; the checks run again. */
    fun edit(change: (WorkflowDraft) -> WorkflowDraft) = state.update {
        val draft = change(it.draft)
        it.copy(draft = draft, problems = WorkflowChecks.check(draft, it.others, it.editing?.id), error = null)
    }

    /** Changes one node of the draft. */
    fun editNode(id: String, change: (DraftNode) -> DraftNode) = edit { draft -> draft.copy(nodes = draft.nodes.map { if (it.id == id) change(it) else it }) }

    fun save() {
        val current = state.value
        if (!current.canSave) return
        state.update { it.copy(saving = true, error = null, conflict = false) }
        viewModelScope.launch {
            try {
                val editing = current.editing
                val write = if (editing == null) {
                    saveWorkflow.create(route.accountId, current.draft, current.others)
                } else {
                    saveWorkflow.update(route.accountId, editing, current.draft, current.others)
                }
                when (write) {
                    is WorkflowWrite.Applied -> state.update { it.copy(saving = false, savedId = write.value) }
                    is WorkflowWrite.Held -> state.update { it.copy(saving = false, held = held(write)) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: AppException.WorkflowInvalid) {
                state.update { it.copy(saving = false, problems = error.problems, error = error.userMessage()) }
            } catch (error: Exception) {
                val stale = (error as? AppException.WorkflowEditRefused)?.reason == WorkflowEditRefusal.STALE
                state.update { it.copy(saving = false, conflict = stale, error = error.userMessage()) }
            }
        }
    }

    private fun held(write: WorkflowWrite.Held): HeldChange {
        val origin = accounts.find(route.accountId)?.origin.orEmpty()
        return HeldChange(confirmationLink(origin, write.confirmUrl), write.expiresAt)
    }

    /** After a refused save over a newer version: read the stored one again, dropping the edits. */
    fun reloadAfterConflict() = load()
}
