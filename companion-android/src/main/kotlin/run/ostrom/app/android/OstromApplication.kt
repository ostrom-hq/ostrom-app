// SPDX-License-Identifier: Apache-2.0
package run.ostrom.app.android

import android.app.Application
import run.ostrom.app.di.initKoin
import run.ostrom.app.di.platformModule
import run.ostrom.app.infrastructure.crypto.installBouncyCastle

class OstromApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before Koin: Ed25519 for the receipts of the history below API 33.
        installBouncyCastle()
        initKoin(platformModule(applicationContext))
    }
}
