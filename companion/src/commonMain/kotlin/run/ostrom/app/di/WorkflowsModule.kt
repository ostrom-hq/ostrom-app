// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion di/Modules.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.domain.usecases.workflows.CanManageRunsUseCase
import run.ostrom.app.domain.usecases.workflows.CancelWorkflowRunUseCase
import run.ostrom.app.domain.usecases.workflows.DeleteWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.LoadEditableWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowEditRightsUseCase
import run.ostrom.app.domain.usecases.workflows.SaveWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.FollowWorkflowRunsUseCase
import run.ostrom.app.domain.usecases.workflows.GetWorkflowRunUseCase
import run.ostrom.app.domain.usecases.workflows.GetWorkflowUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowRunsUseCase
import run.ostrom.app.domain.usecases.workflows.LoadWorkflowsUseCase
import run.ostrom.app.domain.usecases.workflows.StartWorkflowRunUseCase
import run.ostrom.app.domain.usecases.workflows.WorkflowAccess
import run.ostrom.app.infrastructure.repositories.RemoteWorkflowEditorRepository
import run.ostrom.app.infrastructure.repositories.RemoteWorkflowRepository
import run.ostrom.app.presentation.navigation.WorkflowEditRoute
import run.ostrom.app.presentation.navigation.WorkflowRoute
import run.ostrom.app.presentation.navigation.WorkflowRunRoute
import run.ostrom.app.presentation.navigation.WorkflowStartRoute
import run.ostrom.app.presentation.viewmodels.WorkflowEditViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowRunViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowRunsViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowStartViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowViewModel
import run.ostrom.app.presentation.viewmodels.WorkflowsViewModel

/** The workflows (`workflows.v1`): the Workflows tab, a workflow with its runs and Start, the start form, and a run with Cancel. */
val workflowsModule = module {
    // Bound by its class: the demo module binds the interface to a router that keeps the demo account's in memory.
    single { RemoteWorkflowRepository(get()) }
    single { WorkflowAccess(get(), get(), get()) }
    single { LoadWorkflowsUseCase(get()) }
    single { GetWorkflowUseCase(get()) }
    single { LoadWorkflowRunsUseCase(get()) }
    single { GetWorkflowRunUseCase(get()) }
    single { FollowWorkflowRunsUseCase(get(), get(), get()) }
    single { CanManageRunsUseCase(get(), get()) }
    single { StartWorkflowRunUseCase(get()) }
    single { CancelWorkflowRunUseCase(get()) }
    viewModel { WorkflowsViewModel(get(), get(), get(), get()) }
    // The member's runs on the tab; a workflow's own runs on its screen.
    viewModel { params -> WorkflowRunsViewModel(params.getOrNull<WorkflowRoute>(), get(), get(), get()) }
    viewModel { (route: WorkflowRoute) -> WorkflowViewModel(route, get(), get(), get(), get(), get()) }
    viewModel { (route: WorkflowStartRoute) -> WorkflowStartViewModel(route, get(), get()) }
    viewModel { (route: WorkflowRunRoute) -> WorkflowRunViewModel(route, get(), get(), get(), get()) }
    // The editor: bound by its class, as the runs' repository (the demo module binds the interface).
    single { RemoteWorkflowEditorRepository(get(), get(), get()) }
    single { LoadWorkflowEditRightsUseCase(get(), get()) }
    single { LoadEditableWorkflowUseCase(get(), get()) }
    single { SaveWorkflowUseCase(get(), get()) }
    single { DeleteWorkflowUseCase(get(), get()) }
    viewModel { (route: WorkflowEditRoute) -> WorkflowEditViewModel(route, get(), get(), get(), get(), get()) }
}
