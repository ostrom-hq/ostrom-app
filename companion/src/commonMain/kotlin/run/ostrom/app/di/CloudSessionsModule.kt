// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion di/Modules.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.di

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import run.ostrom.app.domain.usecases.files.SessionFilesUseCase
import run.ostrom.app.domain.usecases.sessions.AnswerPermissionUseCase
import run.ostrom.app.domain.usecases.sessions.CloudSessionAccess
import run.ostrom.app.domain.usecases.sessions.ControlConversationUseCase
import run.ostrom.app.domain.usecases.sessions.SendMessageUseCase
import run.ostrom.app.domain.usecases.transcript.FollowTranscriptUseCase
import run.ostrom.app.domain.usecases.transcript.ListAgentsUseCase
import run.ostrom.app.domain.usecases.transcript.LoadTranscriptUseCase
import run.ostrom.app.domain.usecases.sessions.ControlCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.CreateCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.CreateDeviceSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadDeviceFoldersUseCase
import run.ostrom.app.domain.usecases.sessions.LoadHostDevicesUseCase
import run.ostrom.app.domain.usecases.sessions.LoadResumableSessionsUseCase
import run.ostrom.app.domain.usecases.sessions.GetCloudSessionUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionOptionsUseCase
import run.ostrom.app.domain.usecases.sessions.LoadCloudSessionsUseCase
import run.ostrom.app.domain.usecases.sessions.SetCloudSessionGitHubAccessUseCase
import run.ostrom.app.infrastructure.repositories.RemoteCloudSessionRepository
import run.ostrom.app.infrastructure.repositories.RemoteSessionFileRepository
import run.ostrom.app.infrastructure.repositories.RemoteDeviceRepository
import run.ostrom.app.infrastructure.repositories.RemoteTranscriptRepository
import run.ostrom.app.presentation.navigation.CloudSessionRoute
import run.ostrom.app.presentation.navigation.NewCloudSessionRoute
import run.ostrom.app.presentation.viewmodels.CloudConversationUseCases
import run.ostrom.app.presentation.viewmodels.CloudSessionViewModel
import run.ostrom.app.presentation.viewmodels.CloudSessionsViewModel
import run.ostrom.app.presentation.viewmodels.DeviceSessionUseCases
import run.ostrom.app.presentation.viewmodels.NewCloudSessionViewModel

/** The person's cloud sessions (`runners.cloud.v1`): their list in the Sessions tab, a session's record, the new session form. */
val cloudSessionsModule = module {
    // Bound by its class: the demo module binds the interface to a router that keeps the demo account's in memory.
    single { RemoteCloudSessionRepository(get()) }
    single { CloudSessionAccess(get(), get(), get()) }
    single { LoadCloudSessionsUseCase(get()) }
    single { GetCloudSessionUseCase(get()) }
    single { ControlCloudSessionUseCase(get()) }
    single { LoadCloudSessionOptionsUseCase(get()) }
    single { CreateCloudSessionUseCase(get()) }
    single { SetCloudSessionGitHubAccessUseCase(get()) }
    viewModel { CloudSessionsViewModel(get(), get()) }
    // Sessions started on one of the member's online devices (`runners.cloud.remote.daemon.v1`).
    single { RemoteDeviceRepository(get()) }
    single { LoadHostDevicesUseCase(get(), get()) }
    single { LoadDeviceFoldersUseCase(get(), get()) }
    single { LoadResumableSessionsUseCase(get(), get()) }
    single { CreateDeviceSessionUseCase(get(), get()) }
    single { DeviceSessionUseCases(get(), get(), get(), get()) }
    // A conversation session (`runners.cloud.conversation.v1`): its transcript, messages, permissions and controls.
    single { RemoteTranscriptRepository(get()) }
    single { LoadTranscriptUseCase(get(), get(), get()) }
    single { FollowTranscriptUseCase(get(), get()) }
    single { ListAgentsUseCase(get(), get(), get()) }
    single { SendMessageUseCase(get(), get(), get(), get()) }
    single { ControlConversationUseCase(get(), get(), get()) }
    single { AnswerPermissionUseCase(get(), get(), get(), get()) }
    // Files sent with messages (`runners.cloud.files.v1`): the composer's paperclip.
    single { RemoteSessionFileRepository(get()) }
    single { SessionFilesUseCase(get(), get(), get(), get()) }
    single { CloudConversationUseCases(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { (route: CloudSessionRoute) -> CloudSessionViewModel(route, get(), get(), get(), get(), get()) }
    viewModel { (route: NewCloudSessionRoute) -> NewCloudSessionViewModel(route, get(), get(), get(), get(), get()) }
}
