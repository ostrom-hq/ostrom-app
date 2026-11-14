// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.domain.usecases.setup.LoadMemberEnvironmentUseCase
import run.ostrom.app.domain.usecases.setup.LoadPersonalMcpServersUseCase
import run.ostrom.app.infrastructure.repositories.RemoteMemberSetupRepository
import run.ostrom.app.presentation.navigation.MemberEnvironmentRoute
import run.ostrom.app.presentation.navigation.PersonalMcpServersRoute
import run.ostrom.app.presentation.viewmodels.MemberEnvironmentViewModel
import run.ostrom.app.presentation.viewmodels.PersonalMcpServersViewModel

/** The member's environment (`environment.v1`) and personal MCP servers (`mcp.personal.v1`), read only. */
val memberSetupModule = module {
    // Bound by its class: the demo module binds the interface to a router that keeps the demo account's in memory.
    single { RemoteMemberSetupRepository(get()) }
    single { LoadMemberEnvironmentUseCase(get(), get(), get()) }
    single { LoadPersonalMcpServersUseCase(get(), get(), get()) }
    viewModel { (route: MemberEnvironmentRoute) -> MemberEnvironmentViewModel(route, get(), get()) }
    viewModel { (route: PersonalMcpServersRoute) -> PersonalMcpServersViewModel(route, get(), get()) }
}
