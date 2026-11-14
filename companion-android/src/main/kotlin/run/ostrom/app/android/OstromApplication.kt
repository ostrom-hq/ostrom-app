// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.android

import android.app.Application
import org.koin.dsl.bind
import org.koin.dsl.module
import run.ostrom.app.di.initKoin
import run.ostrom.app.di.platformModule
import run.ostrom.app.di.startPush
import run.ostrom.app.domain.services.PushPlatform
import run.ostrom.app.infrastructure.crypto.installBouncyCastle
import run.ostrom.app.infrastructure.push.AndroidPushPlatform

class OstromApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before Koin: Ed25519 for the receipts of the history below API 33.
        installBouncyCastle()
        // Push only in a build that carries the Firebase project; elsewhere no PushPlatform, and nothing registers.
        val push = if (PushNotifications.configured) module { single { AndroidPushPlatform(get()) } bind PushPlatform::class } else null
        val koin = initKoin(platformModule(applicationContext)) { push?.let { modules(it) } }.koin
        if (push == null) return
        PushNotifications.start(this, koin.get())
        startPush(koin)
    }
}
