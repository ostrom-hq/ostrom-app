// SPDX-License-Identifier: Apache-2.0
// Shaped like TeamCode's companion di/Modules.kt and di/Koin.kt (Apache-2.0); see NOTICE.
package run.ostrom.app.di

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.koin.dsl.module
import run.ostrom.app.demo.isDemo
import run.ostrom.app.domain.repositories.PushRepository
import run.ostrom.app.domain.services.PushPlatform
import run.ostrom.app.domain.usecases.push.ClearPushUseCase
import run.ostrom.app.domain.usecases.push.ReadPushUseCase
import run.ostrom.app.domain.usecases.push.ResolveNotificationUseCase
import run.ostrom.app.domain.usecases.push.ShareSignedInAccountsUseCase
import run.ostrom.app.domain.usecases.push.SyncPushUseCase
import run.ostrom.app.infrastructure.repositories.RemotePushRepository

/**
 * Push to this phone (`notifications.push.v1`). The [PushPlatform] is bound by the phone's shell only when its build
 * carries a Firebase project; [SyncPushUseCase] and [ReadPushUseCase] are resolved only then.
 */
val pushModule = module {
    single<PushRepository> { RemotePushRepository(get()) }
    single { SyncPushUseCase(get(), get(), get(), get(), get(), skip = { isDemo(it) }) }
    single { ShareSignedInAccountsUseCase(get(), get(), skip = { isDemo(it) }) }
    single { ClearPushUseCase(get()) }
    single { ReadPushUseCase(get(), get()) }
    single { ResolveNotificationUseCase(get()) }
}

/**
 * Keeps the servers of the signed-in accounts told where to push ([SyncPushUseCase]), and the phone's platform told
 * which accounts are signed in ([ShareSignedInAccountsUseCase]), on a phone whose shell binds a
 * [PushPlatform]; nothing elsewhere. Called once after [initKoin].
 */
fun startPush(koin: Koin) {
    if (koin.getOrNull<PushPlatform>() == null) return
    val sync = koin.get<SyncPushUseCase>()
    val share = koin.get<ShareSignedInAccountsUseCase>()
    val scope = koin.get<CoroutineScope>()
    scope.launch { sync() }
    scope.launch { share() }
}
